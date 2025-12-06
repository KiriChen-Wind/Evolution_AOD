package com.wind.evolution.aod.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AOD 状态管理工具类
 * 用于检测和控制 AOD 功能
 */
object AODManager {
    
    private const val TAG = "AODManager"
    
    /**
     * 检查设备是否处于 AOD 状态
     * 新方法：使用 KeyguardManager 和 PowerManager API
     * AOD 状态 = 屏幕关闭 + 设备锁定 + AOD 功能开启
     */
    suspend fun isInAODState(context: Context): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "isInAODState")
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            
            // 1. 检查屏幕是否关闭（非交互状态）
            val isScreenOff = !powerManager.isInteractive
            Logger.d(TAG, "屏幕状态: isInteractive=${powerManager.isInteractive}, isScreenOff=$isScreenOff")
            
            // 2. 检查设备是否锁定
            val isDeviceLocked = keyguardManager.isKeyguardLocked
            Logger.d(TAG, "锁屏状态: isKeyguardLocked=$isDeviceLocked")
            
            // 3. 检查 AOD 功能是否开启
            val isAODEnabled = isAODEnabled(context)
            Logger.d(TAG, "AOD 设置: isAODEnabled=$isAODEnabled")
            
            // 4. 综合判断：屏幕关闭 + 设备锁定 + AOD 开启 = AOD 状态
            val inAOD = isScreenOff && isDeviceLocked && isAODEnabled
            Logger.i(TAG, "AOD 状态检测: screenOff=$isScreenOff, locked=$isDeviceLocked, aodEnabled=$isAODEnabled, inAOD=$inAOD")
            
            Logger.exit(TAG, "isInAODState")
            inAOD
        } catch (e: Exception) {
            Logger.e(TAG, "检测 AOD 状态失败", e)
            false
        }
    }
    
    /**
     * 备用方法：使用 dumpsys 检测（保留作为降级方案）
     */
    suspend fun isInAODStateByDumpsys(): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "isInAODStateByDumpsys")
        try {
            Logger.d(TAG, "执行 dumpsys 检查 AOD 状态（备用方法）")
            val screenOnResult = RootCommandExecutor.executeCommand("dumpsys deviceidle | grep mScreenOn")
            val screenLockedResult = RootCommandExecutor.executeCommand("dumpsys deviceidle | grep mScreenLocked")
            
            if (!screenOnResult.success || !screenLockedResult.success) {
                Logger.e(TAG, "无法获取设备状态")
                return@withContext false
            }
            
            val isScreenOff = screenOnResult.output.contains("mScreenOn=false")
            val isScreenLocked = screenLockedResult.output.contains("mScreenLocked=true")
            
            val inAOD = isScreenOff && isScreenLocked
            Logger.i(TAG, "AOD 状态检测(dumpsys): screenOff=$isScreenOff, locked=$isScreenLocked, inAOD=$inAOD")
            
            Logger.exit(TAG, "isInAODStateByDumpsys")
            inAOD
        } catch (e: Exception) {
            Logger.e(TAG, "检测 AOD 状态失败", e)
            false
        }
    }
    
    /**
     * 检查 AOD 功能是否在系统设置中开启
     */
    fun isAODEnabled(context: Context): Boolean {
        return try {
            val aodEnabled = Settings.Secure.getInt(
                context.contentResolver,
                "doze_always_on",
                0
            )
            aodEnabled == 1
        } catch (e: Exception) {
            Log.e(TAG, "检查 AOD 设置失败", e)
            false
        }
    }
    
    /**
     * 关闭 AOD 功能（不刷新）
     * 需要 Root 权限
     */
    suspend fun disableAOD(context: Context): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "disableAOD")
        try {
            Logger.d(TAG, "执行关闭 AOD 命令")
            val result = RootCommandExecutor.executeCommand(
                "settings put secure doze_always_on 0"
            )
            
            if (result.success) {
                Logger.i(TAG, "AOD 已关闭（doze_always_on=0）")
            } else {
                Logger.e(TAG, "关闭 AOD 失败")
            }
            
            Logger.exit(TAG, "disableAOD")
            result.success
        } catch (e: Exception) {
            Logger.e(TAG, "关闭 AOD 异常", e)
            false
        }
    }
    
    /**
     * 开启 AOD 功能
     * 需要 Root 权限
     */
    suspend fun enableAOD(): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "enableAOD")
        try {
            Logger.d(TAG, "执行开启 AOD 命令")
            val result = RootCommandExecutor.executeCommand(
                "settings put secure doze_always_on 1"
            )
            
            if (result.success) {
                Logger.i(TAG, "AOD 已开启（doze_always_on=1）")
            } else {
                Logger.e(TAG, "开启 AOD 失败")
            }
            
            Logger.exit(TAG, "enableAOD")
            result.success
        } catch (e: Exception) {
            Logger.e(TAG, "开启 AOD 异常", e)
            false
        }
    }
    
    /**
     * 刷新 AOD 状态
     * 通过模拟按两次电源键来使 AOD 设置生效
     * 优化：显示黑色遮罩 + 1秒间隔
     */
    suspend fun refreshAODState(context: Context): Boolean {
        return RootCommandExecutor.refreshAODByPowerKey(context)
    }
    
    /**
     * 临时关闭 AOD（自动管理模式）
     * 1. 关闭 AOD 设置
     * 2. 刷新状态（按两次电源键使当前屏幕立即生效）
     * 注意：AOD 设置会在屏幕点亮时恢复，不在此处恢复
     */
    suspend fun temporaryDisableAOD(context: Context): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "temporaryDisableAOD")
        try {
            // 步骤 1: 关闭 AOD 设置
            val disabled = disableAOD(context)
            if (!disabled) {
                Logger.e(TAG, "关闭 AOD 设置失败")
                return@withContext false
            }
            
            // 步骤 2: 等待设置生效
            Logger.d(TAG, "等待 300ms 设置生效")
            kotlinx.coroutines.delay(300)
            
            // 步骤 3: 刷新状态（按两次电源键）
            Logger.d(TAG, "刷新 AOD 状态（使当前屏幕立即关闭）")
            val refreshed = refreshAODState(context)
            if (!refreshed) {
                Logger.w(TAG, "刷新 AOD 状态失败，但继续执行")
            }
            
            // 步骤 4: 等待刷新完成
            Logger.d(TAG, "等待 500ms 确保刷新完成")
            kotlinx.coroutines.delay(500)
            
            Logger.i(TAG, "AOD 已关闭，将在屏幕点亮时恢复设置")
            Logger.exit(TAG, "temporaryDisableAOD")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "temporaryDisableAOD 异常", e)
            false
        }
    }
}
