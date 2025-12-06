package com.wind.evolution.aod.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.wind.evolution.aod.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 黑色遮罩 Activity
 * 用于在按电源键时遮住屏幕，避免闪烁
 */
class BlackOverlayActivity : ComponentActivity() {
    
    private val TAG = "BlackOverlayActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.d(TAG, "黑色遮罩 Activity 创建")
        
        // 设置窗口属性（在 setContent 之前）
        window.apply {
            // 在锁屏上显示
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            // 关闭屏幕时保持显示
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 全屏显示
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            // 在最顶层
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            // 忽略触摸事件（但不影响系统按键）
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            
            // 设置亮度为 0（最暗）
            val layoutParams = attributes
            layoutParams.screenBrightness = 0f  // 0.0 = 最暗，1.0 = 最亮
            attributes = layoutParams
        }
        
        // 设置纯黑色界面
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }
        
        Logger.i(TAG, "黑色遮罩已显示")
        
        // 3秒后自动关闭（防止卡死）
        CoroutineScope(Dispatchers.Main).launch {
            delay(AUTO_FINISH_DELAY)
            if (!isFinishing) {
                Logger.w(TAG, "遮罩超时自动关闭")
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "黑色遮罩 Activity 销毁")
    }
    
    companion object {
        /**
         * 自动关闭标记
         * 用于在一定时间后自动销毁 Activity
         */
        const val AUTO_FINISH_DELAY = 3000L // 3 秒后自动关闭
    }
}
