package com.wind.evolution.aod.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wind.evolution.aod.R
import com.wind.evolution.aod.data.PreferencesManager
import com.wind.evolution.aod.receiver.ScreenStateReceiver
import com.wind.evolution.aod.ui.MainActivity
import com.wind.evolution.aod.util.AODManager
import com.wind.evolution.aod.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * AOD 监控服务
 * 在后台监控传感器状态，自动管理 AOD 开关
 */
class AODMonitorService : Service(), SensorEventListener {
    
    private val TAG = "AODMonitorService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "evolution_aod_service"
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    
    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    
    // 屏幕状态监听器
    private var screenStateReceiver: ScreenStateReceiver? = null
    
    // 传感器状态
    private var isInPocket = false
    private var isInDarkEnvironment = false
    private var lastActivityTime = System.currentTimeMillis()
    
    // 传感器注册状态
    @Volatile
    private var sensorsRegistered = false
    
    // 全局已处理标记（任意检测执行后，所有检测都不再执行）
    @Volatile
    private var anyDetectionHandled = false
    
    // 定时任务
    private var pocketCheckJob: Job? = null
    private var idleCheckJob: Job? = null
    private var darkCheckJob: Job? = null
    
    // 标记是否正在执行脚本（执行期间暂停屏幕状态监听）
    @Volatile
    private var isExecutingScript = false
    
    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "========== 服务开始创建 ==========")
        
        try {
            Logger.d(TAG, "获取 PreferencesManager 单例")
            preferencesManager = PreferencesManager.getInstance(this)
            
            Logger.d(TAG, "获取系统服务")
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            Logger.d(TAG, "初始化传感器")
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            
            Logger.i(TAG, "传感器状态 - 距离传感器: ${if (proximitySensor != null) "可用" else "不可用"}, 光线传感器: ${if (lightSensor != null) "可用" else "不可用"}")
            
            Logger.d(TAG, "创建通知并启动前台服务")
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Logger.i(TAG, "前台服务已启动")
            
            // 注册屏幕状态监听器
            Logger.d(TAG, "注册屏幕状态监听器")
            screenStateReceiver = ScreenStateReceiver { isExecutingScript }
            registerReceiver(screenStateReceiver, ScreenStateReceiver.createIntentFilter())
            Logger.i(TAG, "屏幕状态监听器已注册")
            
            Logger.d(TAG, "启动监控协程")
            serviceScope.launch {
                try {
                    startMonitoring()
                } catch (e: Exception) {
                    Logger.e(TAG, "监控任务异常", e)
                }
            }
            
            Logger.i(TAG, "========== 服务创建完成 ==========")
        } catch (e: Exception) {
            Logger.e(TAG, "服务创建失败", e)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.i(TAG, "onStartCommand 调用 - startId: $startId")
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "========== 服务开始销毁 ==========")
        
        try {
            stopMonitoring()
            
            // 注销屏幕状态监听器
            try {
                screenStateReceiver?.let {
                    unregisterReceiver(it)
                    Logger.d(TAG, "屏幕状态监听器已注销")
                }
                screenStateReceiver = null
            } catch (e: Exception) {
                Logger.e(TAG, "注销屏幕状态监听器失败", e)
            }
            
            serviceScope.cancel()
            Logger.i(TAG, "服务销毁完成")
        } catch (e: Exception) {
            Logger.e(TAG, "服务销毁时异常", e)
        }
    }
    
    /**
     * 开始监控
     */
    private suspend fun startMonitoring() {
        Logger.enter(TAG, "startMonitoring")
        
        try {
            // 监听口袋检测开关变化
            serviceScope.launch {
                preferencesManager.isPocketDetectionEnabled.collect { enabled ->
                    Logger.i(TAG, "口袋检测开关变化: $enabled")
                    if (enabled && proximitySensor != null) {
                        Logger.d(TAG, "注册距离传感器监听")
                        sensorManager.registerListener(
                            this@AODMonitorService,
                            proximitySensor,
                            SensorManager.SENSOR_DELAY_NORMAL
                        )
                    } else {
                        Logger.d(TAG, "注销距离传感器监听")
                        proximitySensor?.let { sensorManager.unregisterListener(this@AODMonitorService, it) }
                        // 取消正在进行的检测
                        pocketCheckJob?.cancel()
                        isInPocket = false
                    }
                }
            }
            
            // 监听暗光检测开关变化
            serviceScope.launch {
                preferencesManager.isDarkDetectionEnabled.collect { enabled ->
                    Logger.i(TAG, "暗光检测开关变化: $enabled")
                    if (enabled && lightSensor != null) {
                        Logger.d(TAG, "注册光线传感器监听")
                        sensorManager.registerListener(
                            this@AODMonitorService,
                            lightSensor,
                            SensorManager.SENSOR_DELAY_NORMAL
                        )
                    } else {
                        Logger.d(TAG, "注销光线传感器监听")
                        lightSensor?.let { sensorManager.unregisterListener(this@AODMonitorService, it) }
                        // 取消正在进行的检测
                        darkCheckJob?.cancel()
                        isInDarkEnvironment = false
                    }
                }
            }
            
            // 监听闲置检测开关变化（直接启动，内部会检查开关）
            Logger.d(TAG, "启动闲置检测")
            startIdleCheck()
            
            // 启动定期 AOD 状态检查
            Logger.d(TAG, "启动 AOD 状态检查")
            startAODStateCheck()
            
            Logger.i(TAG, "监控启动完成")
        } catch (e: Exception) {
            Logger.e(TAG, "启动监控时异常", e)
            throw e
        }
        
        Logger.exit(TAG, "startMonitoring")
    }
    
    /**
     * 注册传感器
     */
    private suspend fun registerSensors() {
        if (sensorsRegistered) {
            return  // 已经注册，不重复执行
        }
        
        val isPocketEnabled = preferencesManager.isPocketDetectionEnabled.first()
        val isDarkEnabled = preferencesManager.isDarkDetectionEnabled.first()
        
        if (isPocketEnabled && proximitySensor != null) {
            Logger.d(TAG, "注册距离传感器监听")
            sensorManager.registerListener(
                this@AODMonitorService,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        
        if (isDarkEnabled && lightSensor != null) {
            Logger.d(TAG, "注册光线传感器监听")
            sensorManager.registerListener(
                this@AODMonitorService,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        
        sensorsRegistered = true
    }
    
    /**
     * 注销传感器
     */
    private fun unregisterSensors() {
        if (!sensorsRegistered) {
            return  // 没有注册，不需要注销
        }
        
        Logger.d(TAG, "注销所有传感器")
        proximitySensor?.let { sensorManager.unregisterListener(this@AODMonitorService, it) }
        lightSensor?.let { sensorManager.unregisterListener(this@AODMonitorService, it) }
        sensorsRegistered = false
    }
    
    /**
     * 停止监控
     */
    private fun stopMonitoring() {
        Logger.enter(TAG, "stopMonitoring")
        
        try {
            Logger.d(TAG, "取消传感器监听")
            sensorManager.unregisterListener(this)
            
            Logger.d(TAG, "取消所有检测任务")
            pocketCheckJob?.cancel()
            idleCheckJob?.cancel()
            darkCheckJob?.cancel()
            
            Logger.i(TAG, "监控停止完成")
        } catch (e: Exception) {
            Logger.e(TAG, "停止监控时异常", e)
        }
        
        Logger.exit(TAG, "stopMonitoring")
    }
    
    /**
     * 传感器数据变化
     */
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                Logger.d(TAG, "距离传感器数据: ${event.values[0]}")
                handleProximityChange(event.values[0])
            }
            Sensor.TYPE_LIGHT -> {
                Logger.d(TAG, "光线传感器数据: ${event.values[0]} lux")
                handleLightChange(event.values[0])
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理精度变化
    }
    
    /**
     * 处理距离传感器变化
     */
    private fun handleProximityChange(distance: Float) {
        // 只在息屏时检测（不是 interactive 状态）
        if (powerManager.isInteractive) {
            Logger.d(TAG, "屏幕已点亮，忽略口袋检测")
            return
        }
        
        // 如果任意检测已执行，不再检测
        if (anyDetectionHandled) {
            Logger.d(TAG, "已有检测执行过，忽略口袋检测")
            return
        }
        
        val maxRange = proximitySensor?.maximumRange ?: 5f
        val isNear = distance < maxRange
        
        Logger.i(TAG, "距离检测 - 距离:$distance cm, 最大范围:$maxRange cm, 靠近:$isNear")
        
        if (isNear && !isInPocket) {
            // 检测到靠近（可能在口袋）
            isInPocket = true
            Logger.i(TAG, "检测到手机进入口袋")
            startPocketCheck()
        } else if (!isNear && isInPocket) {
            // 检测到远离（离开口袋）
            isInPocket = false
            Logger.i(TAG, "检测到手机离开口袋")
            pocketCheckJob?.cancel()
        }
    }
    
    /**
     * 处理光线传感器变化
     */
    private fun handleLightChange(lux: Float) {
        serviceScope.launch {
            // 只在息屏时检测（不是 interactive 状态）
            if (powerManager.isInteractive) {
                Logger.d(TAG, "屏幕已点亮，忽略暗光检测")
                return@launch
            }
            
            // 如枟任意检测已执行，不再检测
            if (anyDetectionHandled) {
                Logger.d(TAG, "已有检测执行过，忽略暗光检测")
                return@launch
            }
            
            val threshold = preferencesManager.darkThreshold.first()
            val isDark = lux < threshold
            
            Logger.d(TAG, "光线传感器: $lux lux (阈值:$threshold), 暗光:$isDark")
            
            if (isDark && !isInDarkEnvironment) {
                isInDarkEnvironment = true
                Logger.i(TAG, "检测到进入暗光环境")
                startDarkCheck()
            } else if (!isDark && isInDarkEnvironment) {
                isInDarkEnvironment = false
                Logger.i(TAG, "检测到离开暗光环境")
                darkCheckJob?.cancel()
            }
        }
    }
    
    /**
     * 开始口袋检测倒计时
     */
    private fun startPocketCheck() {
        pocketCheckJob?.cancel()
        pocketCheckJob = serviceScope.launch {
            val delaySeconds = preferencesManager.pocketDetectionDelay.first()
            Logger.d(TAG, "开始口袋检测倒计时: ${delaySeconds}秒")
            
            // 每秒检查一次，如果离开口袋就取消
            var elapsed = 0
            while (elapsed < delaySeconds) {
                delay(1000)
                elapsed++
                
                // 如果已经离开口袋，取消检测
                if (!isInPocket) {
                    Logger.d(TAG, "已离开口袋，取消检测")
                    return@launch
                }
                
                // 如果屏幕被点亮，取消检测
                if (powerManager.isInteractive) {
                    Logger.d(TAG, "屏幕已点亮，取消检测")
                    return@launch
                }
            }
            
            // 倒计时结束，检查是否仍在口袋且处于 AOD 状态且未处理过
            if (isInPocket && !anyDetectionHandled && AODManager.isInAODState(this@AODMonitorService)) {
                Logger.d(TAG, "口袋检测触发 - 关闭 AOD")
                anyDetectionHandled = true  // 设置全局已处理标记
                
                // 注销传感器，省电
                unregisterSensors()
                
                isExecutingScript = true  // 设置标记，暂停屏幕监听
                try {
                    delay(1000)  // 脚本执行前等待 1 秒
                    if (AODManager.temporaryDisableAOD(this@AODMonitorService)) {
                        // 脚本已经恢复了 AOD 设置，不需要标记
                        Logger.d(TAG, "脚本执行完成，AOD 设置已自动恢复")
                    }
                    delay(1000)  // 脚本执行后等待 1 秒
                } finally {
                    isExecutingScript = false  // 清除标记，恢复监听
                }
            } else {
                Logger.d(TAG, "倒计时结束但条件不满足，取消执行")
            }
        }
    }
    
    /**
     * 开始闲置检测
     */
    private fun startIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = serviceScope.launch {
            while (isActive) {
                delay(1000L) // 每秒检查一次
                
                // 检查开关是否启用
                val enabled = preferencesManager.isIdleDetectionEnabled.first()
                if (!enabled) {
                    continue  // 不输出日志，直接跳过
                }
                
                // 如果任意检测已执行，不再检测
                if (anyDetectionHandled) {
                    continue
                }
                
                val delaySeconds = preferencesManager.idleDetectionDelay.first()
                val idleTimeSeconds = (System.currentTimeMillis() - lastActivityTime) / 1000
                
                if (idleTimeSeconds >= delaySeconds && AODManager.isInAODState(this@AODMonitorService)) {
                    Logger.d(TAG, "闲置检测触发 - 关闭 AOD (闲置 ${idleTimeSeconds} 秒)")
                    anyDetectionHandled = true  // 设置全局已处理标记
                    
                    // 注销传感器，省电
                    unregisterSensors()
                    
                    isExecutingScript = true  // 设置标记，暂停屏幕监听
                    try {
                        delay(1000)  // 脚本执行前等待 1 秒
                        if (AODManager.temporaryDisableAOD(this@AODMonitorService)) {
                            // 脚本已经恢复了 AOD 设置，不需要标记
                            Logger.d(TAG, "脚本执行完成，AOD 设置已自动恢复")
                        }
                        delay(1000)  // 脚本执行后等待 1 秒
                    } finally {
                        isExecutingScript = false  // 清除标记，恢复监听
                    }
                    // 执行完后不再等待，直接结束本次检测
                    Logger.d(TAG, "闲置检测已处理，等待用户下次点亮屏幕")
                }
            }
        }
    }
    
    /**
     * 开始暗光检测倒计时
     */
    private fun startDarkCheck() {
        darkCheckJob?.cancel()
        darkCheckJob = serviceScope.launch {
            val delaySeconds = preferencesManager.darkDetectionDelay.first()
            Logger.d(TAG, "开始暗光检测倒计时: ${delaySeconds}秒")
            
            // 每秒检查一次，如果离开暗光环境就取消
            var elapsed = 0
            while (elapsed < delaySeconds) {
                delay(1000)
                elapsed++
                
                // 如果已经离开暗光环境，取消检测
                if (!isInDarkEnvironment) {
                    Logger.d(TAG, "已离开暗光环境，取消检测")
                    return@launch
                }
                
                // 如枟屏幕被点亮，取消检测
                if (powerManager.isInteractive) {
                    Logger.d(TAG, "屏幕已点亮，取消检测")
                    return@launch
                }
            }
            
            // 倒计时结束，检查是否仍在暗光环境且处于 AOD 状态且未处理过
            if (isInDarkEnvironment && !anyDetectionHandled && AODManager.isInAODState(this@AODMonitorService)) {
                Logger.d(TAG, "暗光检测触发 - 关闭 AOD")
                anyDetectionHandled = true  // 设置全局已处理标记
                
                // 注销传感器，省电
                unregisterSensors()
                
                isExecutingScript = true  // 设置标记，暂停屏幕监听
                try {
                    delay(1000)  // 脚本执行前等待 1 秒
                    if (AODManager.temporaryDisableAOD(this@AODMonitorService)) {
                        // 脚本已经恢复了 AOD 设置，不需要标记
                        Logger.d(TAG, "脚本执行完成，AOD 设置已自动恢复")
                    }
                    delay(1000)  // 脚本执行后等待 1 秒
                } finally {
                    isExecutingScript = false  // 清除标记，恢复监听
                }
            } else {
                Logger.d(TAG, "倒计时结束但条件不满足，取消执行")
            }
        }
    }
    
    /**
     * 定期检查 AOD 状态
     */
    private fun startAODStateCheck() {
        serviceScope.launch {
            while (isActive) {
                delay(1000) // 每 1 秒检查一次，快速响应屏幕点亮
                
                // 如果正在执行脚本，跳过检查
                if (isExecutingScript) {
                    Logger.d(TAG, "正在执行脚本，跳过状态检查")
                    continue
                }
                
                // 检测屏幕是否被唤醒（点亮），更新活动时间
                if (powerManager.isInteractive) {
                    lastActivityTime = System.currentTimeMillis()
                    
                    // 亮屏时注销传感器，省电
                    unregisterSensors()
                    
                    // 重置全局已处理标记
                    if (anyDetectionHandled) {
                        Logger.i(TAG, "========== 屏幕点亮，重置检测标记 ==========")
                        anyDetectionHandled = false
                                            
                        // 恢复 AOD 设置
                        serviceScope.launch {
                            Logger.d(TAG, "恢复 AOD 设置")
                            if (AODManager.enableAOD()) {
                                Logger.i(TAG, "AOD 设置已恢复，下次息屏时会显示 AOD")
                            } else {
                                Logger.w(TAG, "恢复 AOD 设置失败")
                            }
                        }
                    }
                    
                    // 重置口袋和暗光检测状态，确保下次息屏可以重新检测
                    if (isInPocket) {
                        Logger.d(TAG, "重置口袋检测状态")
                        isInPocket = false
                        pocketCheckJob?.cancel()
                    }
                    if (isInDarkEnvironment) {
                        Logger.d(TAG, "重置暗光检测状态")
                        isInDarkEnvironment = false
                        darkCheckJob?.cancel()
                    }
                } else {
                    // 息屏时重新注册传感器（如果还没触发过）
                    if (!anyDetectionHandled) {
                        registerSensors()
                    }
                }
            }
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        Logger.enter(TAG, "createNotification")
        
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 创建通知渠道
            Logger.d(TAG, "创建通知渠道")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Evolution AOD 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Evolution AOD 服务运行状态"
            }
            notificationManager.createNotificationChannel(channel)
            Logger.d(TAG, "通知渠道创建完成")
            
            // 创建点击意图
            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
            
            Logger.d(TAG, "构建通知")
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Evolution AOD")
                .setContentText("这玩意挺烦，建议关掉此子通知（")
                .setSmallIcon(R.drawable.ic_launcher) // 使用系统图标避免闪退
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
                
            Logger.i(TAG, "通知创建成功")
            Logger.exit(TAG, "createNotification")
            return notification
        } catch (e: Exception) {
            Logger.e(TAG, "创建通知失败", e)
            throw e
        }
    }
}
