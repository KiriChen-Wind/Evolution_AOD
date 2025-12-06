package com.wind.evolution.aod.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wind.evolution.aod.service.AODMonitorService
import com.wind.evolution.aod.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机启动接收器
 * 在设备启动后自动启动 AOD 监控服务
 */
class BootReceiver : BroadcastReceiver() {
    
    private val TAG = "BootReceiver"
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "设备开机，准备启动服务")
            
            receiverScope.launch {
                val preferencesManager = PreferencesManager.getInstance(context)
                val shouldStart = preferencesManager.isServiceRunning.first()
                
                if (shouldStart) {
                    Log.d(TAG, "启动 AOD 监控服务")
                    val serviceIntent = Intent(context, AODMonitorService::class.java)
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}
