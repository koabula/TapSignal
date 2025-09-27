package org.thoughtcrime.securesms.tap.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.signal.core.util.logging.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TAP模块独立数据库管理器
 * 提供完全独立的SQLite数据库，避免与主数据库的连接池竞争
 */
class TapDatabaseManager private constructor(private val context: Context) {
    
    companion object {
        private val TAG = Log.tag(TapDatabaseManager::class.java)
        
        @Volatile
        private var INSTANCE: TapDatabaseManager? = null
        
        fun getInstance(context: Context): TapDatabaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TapDatabaseManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        private const val TAP_DATABASE_NAME = "tap_transport.db"
        private const val TAP_DATABASE_VERSION = 1
        private const val MAX_CONNECTIONS = 4 // 独立连接池大小
    }
    
    private val databaseHelper = TapSQLiteOpenHelper(context)
    private val connectionSemaphore = Semaphore(permits = MAX_CONNECTIONS)
    private val databaseMutex = Mutex()
    
    // 连接池管理
    private val readableDatabase = AtomicReference<SQLiteDatabase?>(null)
    private val writableDatabase = AtomicReference<SQLiteDatabase?>(null)
    private val databaseLock = ReentrantReadWriteLock()
    
    /**
     * 获取可读数据库连接
     */
    suspend fun getReadableDatabase(): SQLiteDatabase {
        return databaseLock.read {
            readableDatabase.get() ?: synchronized(this) {
                readableDatabase.get() ?: databaseHelper.readableDatabase.also {
                    readableDatabase.set(it)
                    Log.d(TAG, "TAP读数据库连接已建立")
                }
            }
        }
    }
    
    /**
     * 获取可写数据库连接
     */
    suspend fun getWritableDatabase(): SQLiteDatabase {
        connectionSemaphore.acquire()
        return try {
            databaseMutex.withLock {
                databaseLock.read {
                    writableDatabase.get() ?: synchronized(this) {
                        writableDatabase.get() ?: databaseHelper.writableDatabase.also {
                            writableDatabase.set(it)
                            Log.d(TAG, "TAP写数据库连接已建立")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            connectionSemaphore.release()
            throw e
        }
    }
    
    /**
     * 释放数据库连接
     */
    fun releaseConnection() {
        connectionSemaphore.release()
    }
    
    /**
     * 执行事务操作
     */
    suspend fun <T> withTransaction(operation: suspend (SQLiteDatabase) -> T): T {
        val db = getWritableDatabase()
        return try {
            db.beginTransaction()
            try {
                val result = operation(db)
                db.setTransactionSuccessful()
                Log.v(TAG, "TAP数据库事务执行成功")
                result
            } finally {
                db.endTransaction()
            }
        } finally {
            releaseConnection()
        }
    }
    
    /**
     * 执行只读操作
     */
    suspend fun <T> withReadOnly(operation: suspend (SQLiteDatabase) -> T): T {
        val db = getReadableDatabase()
        return operation(db)
    }
    
    /**
     * 关闭数据库连接
     */
    fun close() {
        databaseLock.write {
            try {
                readableDatabase.get()?.close()
                writableDatabase.get()?.close()
                databaseHelper.close()
                
                readableDatabase.set(null)
                writableDatabase.set(null)
                
                Log.i(TAG, "TAP数据库连接已关闭")
            } catch (e: Exception) {
                Log.e(TAG, "关闭TAP数据库连接异常", e)
            }
        }
    }
    
    /**
     * 获取数据库文件路径
     */
    fun getDatabasePath(): String {
        return File(context.filesDir, TAP_DATABASE_NAME).absolutePath
    }
    
    /**
     * 获取数据库文件大小
     */
    fun getDatabaseSize(): Long {
        return try {
            File(getDatabasePath()).length()
        } catch (e: Exception) {
            Log.w(TAG, "获取数据库大小异常", e)
            0L
        }
    }
    
    /**
     * 数据库健康检查
     */
    suspend fun performHealthCheck(): Boolean {
        return try {
            withReadOnly { db ->
                val cursor = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'", null)
                cursor.use {
                    it.moveToFirst()
                    val tableCount = it.getInt(0)
                    Log.d(TAG, "TAP数据库健康检查通过，表数量: $tableCount")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TAP数据库健康检查失败", e)
            false
        }
    }
    
    /**
     * 获取连接池状态
     */
    fun getConnectionPoolStatus(): ConnectionPoolStatus {
        return ConnectionPoolStatus(
            maxConnections = MAX_CONNECTIONS,
            availableConnections = connectionSemaphore.availablePermits,
            activeConnections = MAX_CONNECTIONS - connectionSemaphore.availablePermits,
            hasReadConnection = readableDatabase.get() != null,
            hasWriteConnection = writableDatabase.get() != null
        )
    }
    
    /**
     * 优化数据库性能
     */
    suspend fun optimizeDatabase() {
        withReadOnly { db ->
            try {
                // 分析表统计信息
                db.execSQL("ANALYZE")
                
                // 检查WAL文件大小
                val cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                cursor.use {
                    if (it.moveToFirst()) {
                        val result = it.getInt(0)
                        Log.d(TAG, "WAL checkpoint 结果: $result")
                    }
                }
                
                Log.i(TAG, "数据库优化完成")
            } catch (e: Exception) {
                Log.w(TAG, "数据库优化异常", e)
            }
        }
    }
    
    /**
     * 连接池状态数据类
     */
    data class ConnectionPoolStatus(
        val maxConnections: Int,
        val availableConnections: Int,
        val activeConnections: Int,
        val hasReadConnection: Boolean,
        val hasWriteConnection: Boolean
    )
    
    /**
     * TAP专用SQLiteOpenHelper
     */
    private class TapSQLiteOpenHelper(context: Context) : SQLiteOpenHelper(
        context,
        TAP_DATABASE_NAME,
        null,
        TAP_DATABASE_VERSION
    ) {
        
        override fun onCreate(db: SQLiteDatabase) {
            Log.i(TAG, "创建TAP数据库表")
            
            // 创建传输通道表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS transport_channels (
                    channel_id TEXT PRIMARY KEY,
                    recipient_id TEXT NOT NULL,
                    provider_type TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    status INTEGER NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 5,
                    created_at INTEGER NOT NULL,
                    last_active_at INTEGER NOT NULL,
                    success_count INTEGER NOT NULL DEFAULT 0,
                    failure_count INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT DEFAULT NULL,
                    config_json TEXT NOT NULL DEFAULT '{}',
                    version INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())
            
            // 创建索引
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transport_channels_recipient ON transport_channels (recipient_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transport_channels_provider ON transport_channels (provider_type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transport_channels_status ON transport_channels (status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transport_channels_active ON transport_channels (last_active_at)")
            
            // 创建传输Token表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS transport_tokens (
                    token_id TEXT PRIMARY KEY,
                    recipient_aci TEXT NOT NULL,
                    provider_type TEXT NOT NULL,
                    token_data TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    is_received INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            
            // 创建Token索引
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transport_tokens_recipient ON transport_tokens (recipient_aci)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_transport_tokens_provider ON transport_tokens (provider_type)")
            
            Log.i(TAG, "TAP数据库表创建完成")
        }
        
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.i(TAG, "升级TAP数据库: $oldVersion -> $newVersion")
            // 未来版本升级逻辑
        }
        
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // 启用WAL模式提高并发性能
            if (!db.isReadOnly) {
                try {
                    // 使用rawQuery执行PRAGMA语句，因为它们会返回结果集
                    db.rawQuery("PRAGMA journal_mode=WAL", null).use { /* consume result */ }
                    db.rawQuery("PRAGMA synchronous=NORMAL", null).use { /* consume result */ }
                    db.rawQuery("PRAGMA cache_size=2000", null).use { /* consume result */ }
                    db.rawQuery("PRAGMA temp_store=MEMORY", null).use { /* consume result */ }
                    Log.d(TAG, "TAP数据库配置完成 (WAL模式)")
                } catch (e: Exception) {
                    Log.w(TAG, "TAP数据库PRAGMA配置异常", e)
                }
            }
        }
    }
} 