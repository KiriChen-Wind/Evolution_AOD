package com.wind.evolution.aod.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wind.evolution.aod.ui.BlackOverlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Root 命令执行工具类
 * 用于执行需要 Root 权限的 Shell 命令
 */
object RootCommandExecutor {
    
    private const val TAG = "RootCommandExecutor"
    
    /**
     * 检查设备是否已经 Root
     */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "isRooted")
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            outputStream.close()
            process.waitFor()
            val result = process.exitValue() == 0
            Logger.i(TAG, "Root 权限检查结果: $result")
            Logger.exit(TAG, "isRooted")
            result
        } catch (e: Exception) {
            Logger.e(TAG, "检查 Root 权限失败", e)
            false
        }
    }
    
    /**
     * 执行单条 Root 命令
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        Logger.d(TAG, "执行命令: $command")
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            while (errorReader.readLine().also { line = it } != null) {
                error.append(line).append("\n")
            }
            
            val exitCode = process.waitFor()
            
            outputStream.close()
            reader.close()
            errorReader.close()
            
            val result = CommandResult(
                success = exitCode == 0,
                output = output.toString().trim(),
                error = error.toString().trim(),
                exitCode = exitCode
            )
            
            if (result.success) {
                Logger.d(TAG, "命令执行成功: $command")
            } else {
                Logger.w(TAG, "命令执行失败: $command, exitCode=$exitCode, error=${result.error}")
            }
            
            result
        } catch (e: Exception) {
            Logger.e(TAG, "执行命令异常: $command", e)
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "未知错误",
                exitCode = -1
            )
        }
    }
    
    /**
     * 执行多条 Root 命令
     * @param commands 要执行的命令列表
     * @return 所有命令的执行结果
     */
    suspend fun executeCommands(commands: List<String>): List<CommandResult> = withContext(Dispatchers.IO) {
        commands.map { executeCommand(it) }
    }
    
    /**
     * 模拟按下电源键
     */
    suspend fun pressKeyCode(keyCode: Int): CommandResult {
        return executeCommand("input keyevent $keyCode")
    }
    
    /**
     * 模拟按下电源键两次（用于刷新 AOD 状态）
     * KEYCODE_POWER = 26
     * 
     * 优化版本：
     * 1. 先显示黑色遮罩避免闪烁
     * 2. 两次按键之间间隔 1 秒避免被识别为双击
     * 3. 完成后手动关闭遮罩
     */
    suspend fun refreshAODByPowerKey(context: Context): Boolean = withContext(Dispatchers.IO) {
        Logger.enter(TAG, "refreshAODByPowerKey")
        var overlayActivity: Intent? = null
        try {
            // 步骤 1: 显示黑色遮罩
            Logger.d(TAG, "显示黑色遮罩")
            overlayActivity = Intent(context, BlackOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(overlayActivity)
            }
            
            // 等待遮罩显示完成
            kotlinx.coroutines.delay(500)
            Logger.d(TAG, "黑色遮罩已显示")
            
            // 步骤 2: 第一次按下电源键（点亮屏幕）
            Logger.d(TAG, "第一次按下电源键")
            val result1 = pressKeyCode(26)
            if (!result1.success) {
                Logger.e(TAG, "第一次按电源键失败")
                return@withContext false
            }
            Logger.d(TAG, "第一次按电源键成功")
            
            // 步骤 3: 等待 1 秒（避免被识别为双击）
            Logger.d(TAG, "等待 1 秒...")
            kotlinx.coroutines.delay(1000)
            
            // 步骤 4: 第二次按下电源键（关闭屏幕）
            Logger.d(TAG, "第二次按下电源键")
            val result2 = pressKeyCode(26)
            if (!result2.success) {
                Logger.e(TAG, "第二次按电源键失败")
                return@withContext false
            }
            Logger.d(TAG, "第二次按电源键成功")
            
            // 步骤 5: 等待一小段时间确保操作完成
            kotlinx.coroutines.delay(500)
            
            Logger.i(TAG, "AOD 刷新完成（总耗时约 2 秒）")
            Logger.exit(TAG, "refreshAODByPowerKey")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "刷新 AOD 失败", e)
            false
        } finally {
            // 步骤 6: 总是关闭黑色遮罩（使用 finally 确保一定执行）
            try {
                Logger.d(TAG, "发送关闭遮罩广播")
                val closeIntent = Intent("com.wind.evolution.aod.CLOSE_OVERLAY")
                context.sendBroadcast(closeIntent)
                kotlinx.coroutines.delay(200)
                Logger.d(TAG, "黑色遮罩关闭指令已发送")
            } catch (e: Exception) {
                Logger.e(TAG, "关闭黑色遮罩失败", e)
            }
        }
    }
}

/**
 * 命令执行结果
 */
data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String,
    val exitCode: Int
)
