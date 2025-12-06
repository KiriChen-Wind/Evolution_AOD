package com.wind.evolution.aod.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.wind.evolution.aod.util.Logger

/**
 * 屏幕和锁屏状态监听器
 * 监听设备锁定/解锁、屏幕开/关事件
 */
class ScreenStateReceiver(private val shouldIgnoreEvents: () -> Boolean = { false }) : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ScreenStateReceiver"
        
        /**
         * 创建 IntentFilter
         */
        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        }
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        // 如果正在执行脚本，忽略事件（防止反复横跳）
        if (shouldIgnoreEvents()) {
            Logger.d(TAG, "正在执行脚本，忽略屏幕事件: ${intent.action}")
            return
        }
        
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // 屏幕关闭（可能进入 AOD 或完全黑屏）
                Logger.i(TAG, "========== 📱 屏幕关闭 ==========")
                Logger.d(TAG, "事件: ACTION_SCREEN_OFF")
                Logger.d(TAG, "时间: ${System.currentTimeMillis()}")
                Logger.d(TAG, "说明: 设备屏幕已关闭，可能进入 AOD 或完全黑屏状态")
            }
            
            Intent.ACTION_SCREEN_ON -> {
                // 屏幕点亮（可能是锁屏界面或解锁后）
                Logger.i(TAG, "========== 💡 屏幕点亮 ==========")
                Logger.d(TAG, "事件: ACTION_SCREEN_ON")
                Logger.d(TAG, "时间: ${System.currentTimeMillis()}")
                Logger.d(TAG, "说明: 设备屏幕已点亮，可能显示锁屏界面")
            }
            
            Intent.ACTION_USER_PRESENT -> {
                // 用户解锁设备（已通过锁屏验证）
                Logger.i(TAG, "========== 🔓 用户解锁设备 ==========")
                Logger.d(TAG, "事件: ACTION_USER_PRESENT")
                Logger.d(TAG, "时间: ${System.currentTimeMillis()}")
                Logger.d(TAG, "说明: 用户已成功解锁设备，可以正常使用")
            }
        }
    }
}
