package com.wind.evolution.aod

import android.app.Application
import android.util.Log
import com.wind.evolution.aod.util.Logger

/**rh
 * Application 类
 */
class EvolutionAODApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化日志系统
        Logger.init(this)
        Logger.i("Application", "========== Evolution AOD Application 启动 ==========")
        Logger.i("Application", "日志文件路径: ${Logger.getLogFilePath()}")
        
        Log.d("EvolutionAOD", "Application 初始化")
    }
}
