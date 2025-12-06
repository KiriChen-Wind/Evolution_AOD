package com.wind.evolution.aod.util

import android.content.Context
import android.util.Log
import com.wind.evolution.aod.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 统一日志工具类
 * 提供全局日志开关和格式化输出
 * 支持自动写入日志文件到应用沙盒目录
 */
object Logger {
    
    private const val APP_TAG = "EvolutionAOD"
    private const val MAX_LOG_FILES = 7 // 最多保留 7 个日志文件
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 单个日志文件最大 5MB
    
    // 是否启用日志（可在发布时关闭）
    var isEnabled = true
    
    // 是否启用文件日志
    var isFileLoggingEnabled = true
    
    private var context: Context? = null
    private var logFile: File? = null
    private var fileWriter: PrintWriter? = null
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    /**
     * 初始化 Logger
     * 应在 Application.onCreate() 中调用
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        if (isFileLoggingEnabled) {
            initLogFile()
            startLogWriter()
        }
    }
    
    /**
     * 初始化日志文件
     */
    private fun initLogFile() {
        try {
            val logsDir = File(context?.filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            
            // 清理旧日志文件
            cleanOldLogFiles(logsDir)
            
            // 创建今天的日志文件
            val fileName = "evolutionaod_${fileNameFormat.format(Date())}.log"
            logFile = File(logsDir, fileName)
            
            // 检查文件大小，如果超过限制则创建新文件
            if (logFile?.exists() == true && logFile?.length() ?: 0 > MAX_LOG_SIZE) {
                val timestamp = System.currentTimeMillis()
                val newFileName = "evolutionaod_${fileNameFormat.format(Date())}_$timestamp.log"
                logFile = File(logsDir, newFileName)
            }
            
            fileWriter = PrintWriter(FileWriter(logFile, true), true)
            
            // 写入分隔符
            fileWriter?.println("\n${"-".repeat(80)}")
            fileWriter?.println("Logger initialized at ${dateFormat.format(Date())}")
            fileWriter?.println("-".repeat(80))
            
        } catch (e: Exception) {
            Log.e(APP_TAG, "初始化日志文件失败", e)
        }
    }
    
    /**
     * 清理旧的日志文件
     */
    private fun cleanOldLogFiles(logsDir: File) {
        try {
            val logFiles = logsDir.listFiles { file ->
                file.name.startsWith("evolutionaod_") && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: return
            
            // 删除超过限制数量的旧文件
            if (logFiles.size > MAX_LOG_FILES) {
                logFiles.drop(MAX_LOG_FILES).forEach { file ->
                    file.delete()
                    Log.d(APP_TAG, "删除旧日志文件: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(APP_TAG, "清理旧日志文件失败", e)
        }
    }
    
    /**
     * 启动日志写入器
     */
    private fun startLogWriter() {
        logScope.launch {
            while (true) {
                try {
                    val log = logQueue.poll()
                    if (log != null) {
                        fileWriter?.println(log)
                    } else {
                        kotlinx.coroutines.delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(APP_TAG, "写入日志失败", e)
                }
            }
        }
    }
    
    /**
     * 写入日志到文件
     */
    private fun writeToFile(level: String, tag: String, message: String) {
        if (!isFileLoggingEnabled || fileWriter == null) return
        
        try {
            val timestamp = dateFormat.format(Date())
            val fullTag = "$APP_TAG-$tag"
            val logLine = "$timestamp $level/$fullTag: $message"
            logQueue.offer(logLine)
        } catch (e: Exception) {
            Log.e(APP_TAG, "添加日志到队列失败", e)
        }
    }
    
    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }
    
    /**
     * 获取所有日志文件
     */
    fun getAllLogFiles(): List<File> {
        val logsDir = File(context?.filesDir, "logs")
        if (!logsDir.exists()) return emptyList()
        
        return logsDir.listFiles { file ->
            file.name.startsWith("evolutionaod_") && file.name.endsWith(".log")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * 清空所有日志文件
     */
    fun clearAllLogs() {
        try {
            getAllLogFiles().forEach { it.delete() }
            Log.i(APP_TAG, "所有日志文件已清空")
            initLogFile() // 重新初始化
        } catch (e: Exception) {
            Log.e(APP_TAG, "清空日志失败", e)
        }
    }
    
    /**
     * 关闭日志写入器
     */
    fun close() {
        try {
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
        } catch (e: Exception) {
            Log.e(APP_TAG, "关闭日志写入器失败", e)
        }
    }
    
    /**
     * Debug 日志
     */
    fun d(tag: String, message: String) {
        if (AppConfig.DEBUG_MODE && isEnabled) {
            Log.d("$APP_TAG-$tag", message)
            writeToFile("D", tag, message)
        }
    }
    
    /**
     * Info 日志
     */
    fun i(tag: String, message: String) {
        if (AppConfig.DEBUG_MODE && isEnabled) {
            Log.i("$APP_TAG-$tag", message)
            writeToFile("I", tag, message)
        }
    }
    
    /**
     * Warning 日志
     */
    fun w(tag: String, message: String) {
        if (AppConfig.DEBUG_MODE && isEnabled) {
            Log.w("$APP_TAG-$tag", message)
            writeToFile("W", tag, message)
        }
    }
    
    /**
     * Warning 日志（带异常）
     */
    fun w(tag: String, message: String, throwable: Throwable?) {
        if (isEnabled) {
            Log.w("$APP_TAG-$tag", message, throwable)
            writeToFile("W", tag, "$message\n${throwable?.stackTraceToString() ?: ""}")
        }
    }
    
    /**
     * Error 日志
     */
    fun e(tag: String, message: String) {
        if (isEnabled) {
            Log.e("$APP_TAG-$tag", message)
            writeToFile("E", tag, message)
        }
    }
    
    /**
     * Error 日志（带异常）
     */
    fun e(tag: String, message: String, throwable: Throwable?) {
        if (isEnabled) {
            Log.e("$APP_TAG-$tag", message, throwable)
            writeToFile("E", tag, "$message\n${throwable?.stackTraceToString() ?: ""}")
        }
    }
    
    /**
     * 记录方法进入
     */
    fun enter(tag: String, method: String) {
        if (AppConfig.DEBUG_MODE && isEnabled) {
            Log.d("$APP_TAG-$tag", ">>> 进入方法: $method")
            writeToFile("D", tag, ">>> 进入方法: $method")
        }
    }
    
    /**
     * 记录方法退出
     */
    fun exit(tag: String, method: String) {
        if (AppConfig.DEBUG_MODE && isEnabled) {
            Log.d("$APP_TAG-$tag", "<<< 退出方法: $method")
            writeToFile("D", tag, "<<< 退出方法: $method")
        }
    }
    
    /**
     * 记录异常堆栈
     */
    fun printStackTrace(tag: String, throwable: Throwable) {
        if (isEnabled) {
            Log.e("$APP_TAG-$tag", "异常堆栈:", throwable)
            writeToFile("E", tag, "异常堆栈:\n${throwable.stackTraceToString()}")
        }
    }
}
