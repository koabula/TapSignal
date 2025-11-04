package org.thoughtcrime.securesms.tap.notification.lifecycle

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import org.signal.core.util.logging.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tap Doze模式处理器
 * 
 * 处理Android Doze模式，确保在省电模式下也能适时检查离线消息
 */
class TapDozeHandler private constructor(
    private val context: Context
) {
    
    companion object {
        private val TAG = Log.tag(TapDozeHandler::class.java)
        
        @Volatile
        private var instance: TapDozeHandler? = null
        
        fun getInstance(context: Context): TapDozeHandler {
            return instance ?: synchronized(this) {
                instance ?: TapDozeHandler(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        // Doze模式下的检查间隔（30分钟）
        private const val DOZE_CHECK_INTERVAL_MS = 30 * 60 * 1000L
        
        // Doze模式广播Action
        private const val ACTION_DOZE_CHECK = "org.thoughtcrime.securesms.tap.DOZE_CHECK"
    }
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    private val isStarted = AtomicBoolean(false)
    private val isInDozeMode = AtomicBoolean(false)
    
    private var dozeReceiver: BroadcastReceiver? = null
    private var checkReceiver: BroadcastReceiver? = null
    
    private var onDozeStateChange: ((Boolean) -> Unit)? = null
    private var onDozeCheck: (() -> Unit)? = null
    
    /**
     * 启动Doze模式处理
     * 
     * @param onStateChange Doze状态变化回调，参数为是否进入Doze模式
     * @param onCheck Doze模式下定期检查回调
     */
    fun start(
        onStateChange: (Boolean) -> Unit,
        onCheck: () -> Unit
    ) {
        if (isStarted.getAndSet(true)) {
            Log.w(TAG, "Doze处理器已启动")
            return
        }
        
        Log.i(TAG, "启动Doze模式处理")
        
        this.onDozeStateChange = onStateChange
        this.onDozeCheck = onCheck
        
        // 注册Doze状态监听
        registerDozeReceiver()
        
        // 注册定期检查接收器
        registerCheckReceiver()
        
        // 检查当前是否在Doze模式
        checkDozeMode()
    }
    
    /**
     * 停止Doze模式处理
     */
    fun stop() {
        if (!isStarted.getAndSet(false)) {
            return
        }
        
        Log.i(TAG, "停止Doze模式处理")
        
        // 注销广播接收器
        unregisterDozeReceiver()
        unregisterCheckReceiver()
        
        // 取消定期检查
        cancelDozeCheck()
        
        onDozeStateChange = null
        onDozeCheck = null
    }
    
    /**
     * 是否在Doze模式
     */
    fun isInDozeMode(): Boolean {
        return isInDozeMode.get()
    }
    
    /**
     * 检查设备是否处于空闲模式
     */
    fun isDeviceIdle(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode
        } else {
            false
        }
    }
    
    /**
     * 注册Doze状态接收器
     */
    private fun registerDozeReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.d(TAG, "设备不支持Doze模式")
            return
        }
        
        dozeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        handleDozeStateChange()
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        
        try {
            context.registerReceiver(dozeReceiver, filter)
            Log.d(TAG, "Doze状态接收器注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "注册Doze状态接收器失败", e)
            dozeReceiver = null
        }
    }
    
    /**
     * 注销Doze状态接收器
     */
    private fun unregisterDozeReceiver() {
        dozeReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "Doze状态接收器注销成功")
            } catch (e: Exception) {
                Log.w(TAG, "注销Doze状态接收器失败", e)
            }
        }
        dozeReceiver = null
    }
    
    /**
     * 注册定期检查接收器
     */
    private fun registerCheckReceiver() {
        checkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_DOZE_CHECK) {
                    handleDozeCheck()
                }
            }
        }
        
        val filter = IntentFilter(ACTION_DOZE_CHECK)
        
        try {
            context.registerReceiver(checkReceiver, filter)
            Log.d(TAG, "定期检查接收器注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "注册定期检查接收器失败", e)
            checkReceiver = null
        }
    }
    
    /**
     * 注销定期检查接收器
     */
    private fun unregisterCheckReceiver() {
        checkReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "定期检查接收器注销成功")
            } catch (e: Exception) {
                Log.w(TAG, "注销定期检查接收器失败", e)
            }
        }
        checkReceiver = null
    }
    
    /**
     * 检查Doze模式
     */
    private fun checkDozeMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        
        val inDozeMode = powerManager.isDeviceIdleMode
        
        if (isInDozeMode.getAndSet(inDozeMode) != inDozeMode) {
            Log.i(TAG, "Doze状态: $inDozeMode")
            onDozeStateChange?.invoke(inDozeMode)
            
            if (inDozeMode) {
                scheduleDozeCheck()
            } else {
                cancelDozeCheck()
            }
        }
    }
    
    /**
     * 处理Doze状态变化
     */
    private fun handleDozeStateChange() {
        checkDozeMode()
    }
    
    /**
     * 处理Doze模式下的定期检查
     */
    private fun handleDozeCheck() {
        if (!isInDozeMode.get()) {
            Log.d(TAG, "不在Doze模式，取消定期检查")
            cancelDozeCheck()
            return
        }
        
        Log.i(TAG, "执行Doze模式定期检查")
        
        try {
            onDozeCheck?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Doze模式检查失败", e)
        }
        
        // 调度下次检查
        scheduleDozeCheck()
    }
    
    /**
     * 调度Doze模式下的定期检查
     */
    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleDozeCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        
        val intent = Intent(ACTION_DOZE_CHECK).apply {
            setPackage(context.packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            val triggerTime = System.currentTimeMillis() + DOZE_CHECK_INTERVAL_MS
            
            // 使用setExactAndAllowWhileIdle确保在Doze模式下也能执行
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "已调度下次Doze检查: ${DOZE_CHECK_INTERVAL_MS / 60000}分钟后")
            }
        } catch (e: Exception) {
            Log.e(TAG, "调度Doze检查失败", e)
        }
    }
    
    /**
     * 取消Doze模式定期检查
     */
    private fun cancelDozeCheck() {
        val intent = Intent(ACTION_DOZE_CHECK).apply {
            setPackage(context.packageName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "已取消Doze检查")
        } catch (e: Exception) {
            Log.w(TAG, "取消Doze检查失败", e)
        }
    }
}

