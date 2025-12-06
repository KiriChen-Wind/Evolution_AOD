package com.wind.evolution.aod.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 全局 DataStore 实例（单例）
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "evolution_aod_settings")

/**
 * 数据存储管理类
 * 使用单例模式避免 DataStore 多实例冲突
 */
class PreferencesManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: PreferencesManager? = null
        
        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
        
        // 口袋检测功能开关
        private val POCKET_DETECTION_ENABLED = booleanPreferencesKey("pocket_detection_enabled")
        // 口袋检测延迟时间（秒）
        private val POCKET_DETECTION_DELAY = intPreferencesKey("pocket_detection_delay")
        
        // 闲置检测功能开关
        private val IDLE_DETECTION_ENABLED = booleanPreferencesKey("idle_detection_enabled")
        // 闲置检测延迟时间（秒）
        private val IDLE_DETECTION_DELAY = intPreferencesKey("idle_detection_delay")
        
        // 暗光检测功能开关
        private val DARK_DETECTION_ENABLED = booleanPreferencesKey("dark_detection_enabled")
        // 暗光检测延迟时间（秒）
        private val DARK_DETECTION_DELAY = intPreferencesKey("dark_detection_delay")
        // 暗光阈值（lux）
        private val DARK_THRESHOLD = intPreferencesKey("dark_threshold")
        
        // 服务是否运行
        private val SERVICE_RUNNING = booleanPreferencesKey("service_running")
        
        // 电池优化引导是否已显示
        private val BATTERY_OPTIMIZATION_GUIDED = booleanPreferencesKey("battery_optimization_guided")
    }
    
    // ========== 口袋检测 ==========
    val isPocketDetectionEnabled: Flow<Boolean> get() = context.dataStore.data.map { preferences ->
        preferences[POCKET_DETECTION_ENABLED] ?: false
    }
    
    suspend fun setPocketDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[POCKET_DETECTION_ENABLED] = enabled
        }
    }
    
    val pocketDetectionDelay: Flow<Int> get() = context.dataStore.data.map { preferences ->
        preferences[POCKET_DETECTION_DELAY] ?: 10 // 默认 10 秒
    }
    
    suspend fun setPocketDetectionDelay(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[POCKET_DETECTION_DELAY] = seconds
        }
    }
    
    // ========== 闲置检测 ==========
    val isIdleDetectionEnabled: Flow<Boolean> get() = context.dataStore.data.map { preferences ->
        preferences[IDLE_DETECTION_ENABLED] ?: false
    }
    
    suspend fun setIdleDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IDLE_DETECTION_ENABLED] = enabled
        }
    }
    
    val idleDetectionDelay: Flow<Int> get() = context.dataStore.data.map { preferences ->
        preferences[IDLE_DETECTION_DELAY] ?: 1800 // 默认 30 分钟（1800秒）
    }
    
    suspend fun setIdleDetectionDelay(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[IDLE_DETECTION_DELAY] = seconds
        }
    }
    
    // ========== 暗光检测 ==========
    val isDarkDetectionEnabled: Flow<Boolean> get() = context.dataStore.data.map { preferences ->
        preferences[DARK_DETECTION_ENABLED] ?: false
    }
    
    suspend fun setDarkDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DARK_DETECTION_ENABLED] = enabled
        }
    }
    
    val darkDetectionDelay: Flow<Int> get() = context.dataStore.data.map { preferences ->
        preferences[DARK_DETECTION_DELAY] ?: 300 // 默认 5 分钟（300秒）
    }
    
    suspend fun setDarkDetectionDelay(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[DARK_DETECTION_DELAY] = seconds
        }
    }
    
    val darkThreshold: Flow<Int> get() = context.dataStore.data.map { preferences ->
        preferences[DARK_THRESHOLD] ?: 5 // 默认 5 lux
    }
    
    suspend fun setDarkThreshold(lux: Int) {
        context.dataStore.edit { preferences ->
            preferences[DARK_THRESHOLD] = lux
        }
    }
    
    // ========== 服务状态 ==========
    val isServiceRunning: Flow<Boolean> get() = context.dataStore.data.map { preferences ->
        preferences[SERVICE_RUNNING] ?: false
    }
    
    suspend fun setServiceRunning(running: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SERVICE_RUNNING] = running
        }
    }
    
    // ========== 电池优化引导 ==========
    val isBatteryOptimizationGuided: Flow<Boolean> get() = context.dataStore.data.map { preferences ->
        preferences[BATTERY_OPTIMIZATION_GUIDED] ?: false
    }
    
    suspend fun setBatteryOptimizationGuided(guided: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BATTERY_OPTIMIZATION_GUIDED] = guided
        }
    }
}
