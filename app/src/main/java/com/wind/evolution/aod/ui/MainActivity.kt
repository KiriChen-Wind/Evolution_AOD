package com.wind.evolution.aod.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.wind.evolution.aod.R
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wind.evolution.aod.data.PreferencesManager
import com.wind.evolution.aod.service.AODMonitorService
import com.wind.evolution.aod.util.Logger
import com.wind.evolution.aod.util.RootCommandExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val TAG = "MainActivity"
    private lateinit var preferencesManager: PreferencesManager
    
    // 通知权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Logger.i(TAG, "通知权限结果: $isGranted")
        if (!isGranted) {
            Toast.makeText(this, "需要通知权限才能正常运行服务", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i(TAG, "========== MainActivity 创建 ==========")
        
        // 启用沉浸式显示（边缘到边缘）
        enableEdgeToEdge()
        
        // 设置状态栏和导航栏透明
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        try {
            preferencesManager = PreferencesManager.getInstance(this)
            Logger.d(TAG, "PreferencesManager 单例获取完成")
            
            // 请求通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Logger.d(TAG, "请求通知权限")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Logger.d(TAG, "通知权限已授予")
                }
            }
            
            Logger.d(TAG, "设置 UI 内容")
            setContent {
                EvolutionAODTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(preferencesManager = preferencesManager)
                    }
                }
            }
            
            Logger.i(TAG, "MainActivity 初始化完成")
        } catch (e: Exception) {
            Logger.e(TAG, "MainActivity 初始化失败", e)
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun EvolutionAODTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    
    // Material You 动态取色
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(preferencesManager: PreferencesManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    
    // 权限状态
    var isRooted by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasBatteryOptimization by remember { mutableStateOf(false) }
    
    // 权限刷新触发器
    var permissionRefreshTrigger by remember { mutableStateOf(0) }
    
    // 所有权限是否满足（包括电池优化）
    val allPermissionsGranted = isRooted && hasNotificationPermission && hasOverlayPermission && hasBatteryOptimization
    
    var serviceRunning by remember { mutableStateOf(false) }
    
    var pocketDetectionEnabled by remember { mutableStateOf(false) }
    var pocketDetectionDelay by remember { mutableStateOf(10) }
    
    var idleDetectionEnabled by remember { mutableStateOf(false) }
    var idleDetectionDelay by remember { mutableStateOf(1800) }  // 存储秒数，默认30分钟
    
    var darkDetectionEnabled by remember { mutableStateOf(false) }
    var darkDetectionDelay by remember { mutableStateOf(300) }  // 存储秒数，默认5分钟
    var darkThreshold by remember { mutableStateOf(5) }
    
    // 显示对话框
    var showPocketDelayDialog by remember { mutableStateOf(false) }
    var showIdleDelayDialog by remember { mutableStateOf(false) }
    var showDarkDelayDialog by remember { mutableStateOf(false) }
    var showDarkThresholdDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationGuideDialog by remember { mutableStateOf(false) }
    
    // 监听生命周期，当从设置页面返回时刷新权限
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                // 当界面恢复时，触发权限刷新
                permissionRefreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // 加载设置（根据 permissionRefreshTrigger 刷新）
    LaunchedEffect(permissionRefreshTrigger) {
        Logger.d("MainScreen", "开始加载设置")
        try {
            // 检查 Root 权限
            Logger.d("MainScreen", "检查 Root 权限")
            isRooted = RootCommandExecutor.isRooted()
            Logger.i("MainScreen", "Root 状态: $isRooted")
            
            // 检查通知权限
            hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.areNotificationsEnabled()
            }
            Logger.i("MainScreen", "通知权限: $hasNotificationPermission")
            
            // 检查悬浮窗权限
            hasOverlayPermission = Settings.canDrawOverlays(context)
            Logger.i("MainScreen", "悬浮窗权限: $hasOverlayPermission")
            
            // 检查电池优化引导状态（如果用户已引导过，就视为已授权）
            hasBatteryOptimization = preferencesManager.isBatteryOptimizationGuided.first()
            Logger.i("MainScreen", "电池优化引导状态: $hasBatteryOptimization")
            
            serviceRunning = preferencesManager.isServiceRunning.first()
            pocketDetectionEnabled = preferencesManager.isPocketDetectionEnabled.first()
            pocketDetectionDelay = preferencesManager.pocketDetectionDelay.first()
            idleDetectionEnabled = preferencesManager.isIdleDetectionEnabled.first()
            idleDetectionDelay = preferencesManager.idleDetectionDelay.first()
            darkDetectionEnabled = preferencesManager.isDarkDetectionEnabled.first()
            darkDetectionDelay = preferencesManager.darkDetectionDelay.first()
            darkThreshold = preferencesManager.darkThreshold.first()
            
            Logger.i("MainScreen", "设置加载完成 - 服务:$serviceRunning, 口袋:$pocketDetectionEnabled, 闲置:$idleDetectionEnabled, 暗光:$darkDetectionEnabled")
        } catch (e: Exception) {
            Logger.e("MainScreen", "加载设置失败", e)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evolution AOD") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        // 处理系统边距，支持沉浸式
        contentWindowInsets = WindowInsets.systemBars
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 权限区域（只在未全部授权时显示）
            if (!allPermissionsGranted) {
                // Root 状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isRooted) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isRooted) "✓ Root 权限已获取" else "✗ 未获取 Root 权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!isRooted) {
                            Text(
                                text = "本应用需要 Root 权限才能正常工作",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            
            // 通知权限卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (hasNotificationPermission) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasNotificationPermission) "✓ 通知权限已授予" else "✗ 未授予通知权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!hasNotificationPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "需要通知权限以显示后台服务",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (!hasNotificationPermission) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Text("授权")
                        }
                    }
                }
            }
            
            // 悬浮窗权限卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (hasOverlayPermission) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasOverlayPermission) "✓ 悬浮窗权限已授予" else "✗ 未授予悬浮窗权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!hasOverlayPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "需要悬浮窗权限以显示黑色遮罩",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (!hasOverlayPermission) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        ) {
                            Text("授权")
                        }
                    }
                }
            }
            
                // 电池优化卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (hasBatteryOptimization) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (hasBatteryOptimization) "✓ 已忽略电池优化" else "✗ 未忽略电池优化",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (!hasBatteryOptimization) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "防止后台服务被系统杀死（推荐）",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (!hasBatteryOptimization) {
                            OutlinedButton(
                                onClick = {
                                    showBatteryOptimizationGuideDialog = true
                                }
                            ) {
                                Text("设置")
                            }
                        }
                    }
                }
            
                // 权限提示
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = "⚠️ 请授予所有必需权限后再使用本应用",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                Divider()
            }
            
            // 服务控制
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用 Evolution AOD", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("后台监控并智能管理 AOD", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = serviceRunning,
                            enabled = allPermissionsGranted,
                            onCheckedChange = { enabled ->
                                Logger.i("MainScreen", "服务开关切换: $enabled")
                                scope.launch {
                                    try {
                                        serviceRunning = enabled
                                        preferencesManager.setServiceRunning(enabled)
                                        
                                        val intent = Intent(context, AODMonitorService::class.java)
                                        if (enabled) {
                                            Logger.i("MainScreen", "准备启动前台服务")
                                            context.startForegroundService(intent)
                                            Toast.makeText(context, "服务已启动", Toast.LENGTH_SHORT).show()
                                            Logger.i("MainScreen", "服务启动成功")
                                        } else {
                                            Logger.i("MainScreen", "准备停止服务")
                                            context.stopService(intent)
                                            Toast.makeText(context, "服务已停止", Toast.LENGTH_SHORT).show()
                                            Logger.i("MainScreen", "服务停止成功")
                                        }
                                    } catch (e: Exception) {
                                        Logger.e("MainScreen", "服务控制失败", e)
                                        Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
                                        // 恢复开关状态
                                        serviceRunning = !enabled
                                    }
                                }
                            }
                        )
                    }
                    
                    // 电池优化引导对话框
                    if (showBatteryOptimizationGuideDialog) {
                        AlertDialog(
                            onDismissRequest = { showBatteryOptimizationGuideDialog = false },
                            title = { Text("忽略电池优化") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "为了防止后台服务被系统杀死，需要忽略电池优化。",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "点击「前往授权」后，请按以下步骤设置：",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "1. 在应用详情页面，点击《电池》或《电池优化》",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "2. 选择「无限制」或「允许后台活动」",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "3. 返回后，权限会自动刷新",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "\n注：不同系统设置项名称可能不同，请根据实际情况调整。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        try {
                                            // 跳转到应用详情页面
                                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                            
                                            // 记录已引导标记，防止每次打开都弹窗
                                            scope.launch {
                                                preferencesManager.setBatteryOptimizationGuided(true)
                                                hasBatteryOptimization = true
                                            }
                                            showBatteryOptimizationGuideDialog = false
                                            Logger.i("MainScreen", "跳转至应用详情页，记录电池优化已引导")
                                        } catch (e: Exception) {
                                            Logger.e("MainScreen", "打开应用详情页失败", e)
                                            Toast.makeText(context, "无法打开设置页面", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("前往授权")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showBatteryOptimizationGuideDialog = false }
                                ) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }
            }
            
            Divider()
            
            // 自动关闭 AOD
            Text("自动关闭 AOD", style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用口袋检测", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("检测到手机在口袋后自动关闭 AOD", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = pocketDetectionEnabled,
                            enabled = allPermissionsGranted,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    pocketDetectionEnabled = enabled
                                    preferencesManager.setPocketDetectionEnabled(enabled)
                                }
                            }
                        )
                    }
                    
                    if (pocketDetectionEnabled) {
                        OutlinedButton(
                            onClick = { showPocketDelayDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("关闭时间: ${pocketDetectionDelay}秒")
                        }
                    }
                }
            }
            
            // 闲置检测
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用闲置检测", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("手机长时间无操作后自动关闭 AOD", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = idleDetectionEnabled,
                            enabled = allPermissionsGranted,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    idleDetectionEnabled = enabled
                                    preferencesManager.setIdleDetectionEnabled(enabled)
                                }
                            }
                        )
                    }
                    
                    if (idleDetectionEnabled) {
                        OutlinedButton(
                            onClick = { showIdleDelayDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("关闭时间: ${idleDetectionDelay / 60}分钟")
                        }
                    }
                }
            }
            
            // 暗光检测
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用暗光检测", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("检测到暗光环境后自动关闭 AOD", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = darkDetectionEnabled,
                            enabled = allPermissionsGranted,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    darkDetectionEnabled = enabled
                                    preferencesManager.setDarkDetectionEnabled(enabled)
                                }
                            }
                        )
                    }
                    
                    if (darkDetectionEnabled) {
                        OutlinedButton(
                            onClick = { showDarkDelayDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("关闭时间: ${darkDetectionDelay / 60}分钟")
                        }
                        
                        OutlinedButton(
                            onClick = { showDarkThresholdDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("暗光阈值: ${darkThreshold} lux")
                        }
                    }
                }
            }
            
            Divider()
            
            // 关于板块
            Text("关于", style = MaterialTheme.typography.headlineSmall)
            
            // 酷安个人主页卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.coolapk.com/u/22989975"))
                        context.startActivity(intent)
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.kirichen),
                            contentDescription = "酷安头像",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("KiriChen | 陈有为", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("开发者", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_right),
                        contentDescription = "跳转",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            // GitHub 和 QQ 群卡片
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // GitHub 卡片
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/KiriChen-Wind/Evolution_AOD"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.github),
                                contentDescription = "GitHub",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("GitHub", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            painter = painterResource(id = R.drawable.arrow_right),
                            contentDescription = "跳转",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                // Q群
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://qun.qq.com/universal-share/share?ac=1&authKey=CJ8L4br2RS4RUxa6TB4et7C%2BC4C3y3QMa6irWquJbtIUrVLfX8fNBu8X8OMf2chS&busi_data=eyJncm91cENvZGUiOiI3NDEyNjcyOTgiLCJ0b2tlbiI6IklwdXQ3WmlqUmhKUEZ1YmJIY2NKOWVJVHoxNTdvckErZS9rdEFzVmVqY1FPVGlqd1M1UXZWZGdGZFZUb1A5WXYiLCJ1aW4iOiIxODQxOTM4MDQwIn0%3D&data=RdBVN-gQso-vJ1d3_ye-b4umooFux-aS1BiFCbiZrpYNAtAB53Vj199KQFufiJ0VjFmYmKYqw-69KVckmOzDcg&svctype=4&tempid=h5_group_info"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.qq),
                                contentDescription = "QQ群",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("QQ群", style = MaterialTheme.typography.titleMedium)
                        }
                        Icon(
                            painter = painterResource(id = R.drawable.arrow_right),
                            contentDescription = "跳转",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
    
    // 对话框
    if (showPocketDelayDialog) {
        TimePickerDialog(
            title = "口袋场景关闭时间",
            currentValue = pocketDetectionDelay,
            unit = "秒",
            range = 2..60,
            onDismiss = { showPocketDelayDialog = false },
            onConfirm = { value ->
                scope.launch {
                    pocketDetectionDelay = value
                    preferencesManager.setPocketDetectionDelay(value)
                    showPocketDelayDialog = false
                }
            }
        )
    }
    
    if (showIdleDelayDialog) {
        TimePickerDialog(
            title = "闲置场景关闭时间",
            currentValue = idleDetectionDelay / 60,  // 显示分钟数
            unit = "分钟",
            range = 2..180,  // 2分钟到3小时
            onDismiss = { showIdleDelayDialog = false },
            onConfirm = { value ->
                scope.launch {
                    idleDetectionDelay = value * 60  // 转换为秒数存储
                    preferencesManager.setIdleDetectionDelay(value * 60)
                    showIdleDelayDialog = false
                }
            }
        )
    }
    
    if (showDarkDelayDialog) {
        TimePickerDialog(
            title = "暗光场景关闭时间",
            currentValue = darkDetectionDelay / 60,  // 显示分钟数
            unit = "分钟",
            range = 2..180,  // 2分钟到3小时
            onDismiss = { showDarkDelayDialog = false },
            onConfirm = { value ->
                scope.launch {
                    darkDetectionDelay = value * 60  // 转换为秒数存储
                    preferencesManager.setDarkDetectionDelay(value * 60)
                    showDarkDelayDialog = false
                }
            }
        )
    }
    
    if (showDarkThresholdDialog) {
        TimePickerDialog(
            title = "暗光阈值(建议5lux)",
            currentValue = darkThreshold,
            unit = "lux",
            range = 1..50,
            onDismiss = { showDarkThresholdDialog = false },
            onConfirm = { value ->
                scope.launch {
                    darkThreshold = value
                    preferencesManager.setDarkThreshold(value)
                    showDarkThresholdDialog = false
                }
            }
        )
    }
}

@Composable
fun TimePickerDialog(
    title: String,
    currentValue: Int,
    unit: String,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by remember { mutableStateOf(currentValue) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前值: $value $unit")
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt() },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
