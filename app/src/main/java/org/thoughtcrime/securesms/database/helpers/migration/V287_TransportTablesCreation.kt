/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.tap.database.TransportChannelTable
import org.thoughtcrime.securesms.tap.database.TransportPollingStateTable
import org.thoughtcrime.securesms.tap.database.TransportTokenTable

/**
 * 创建Transport相关的数据库表，包括通道管理、轮询状态和Token池
 */
@Suppress("ClassName")
object V287_TransportTablesCreation : SignalDatabaseMigration {

  private val TAG = Log.tag(V287_TransportTablesCreation::class)

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "开始创建Transport数据库表...")

    try {
      // 创建传输通道表
      Log.d(TAG, "创建transport_channels表...")
      db.execSQL(TransportChannelTable.CREATE_TABLE)
      
      // 创建通道表索引
      for (index in TransportChannelTable.CREATE_INDEXES) {
        db.execSQL(index)
      }
      Log.d(TAG, "transport_channels表创建完成")

      // 创建轮询状态表
      Log.d(TAG, "创建transport_polling_state表...")
      db.execSQL(TransportPollingStateTable.CREATE_TABLE)
      
      // 创建轮询状态表索引
      for (index in TransportPollingStateTable.CREATE_INDEXES) {
        db.execSQL(index)
      }
      Log.d(TAG, "transport_polling_state表创建完成")

      // 创建Token表
      Log.d(TAG, "创建transport_tokens表...")
      db.execSQL(TransportTokenTable.CREATE_TABLE)
      
      // 创建Token表索引
      for (index in TransportTokenTable.CREATE_INDEXES) {
        db.execSQL(index)
      }
      Log.d(TAG, "transport_tokens表创建完成")

      // 创建消息去重表
      Log.d(TAG, "创建transport_processed_messages表...")
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS transport_processed_messages (" +
          "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
          "duplication_key TEXT UNIQUE NOT NULL, " +
          "processed_timestamp INTEGER NOT NULL, " +
          "created_at INTEGER NOT NULL" +
        ")"
      )
      
      // 创建消息去重表索引
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_processed_messages_key_idx ON transport_processed_messages (duplication_key)")
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_processed_messages_timestamp_idx ON transport_processed_messages (processed_timestamp)")
      Log.d(TAG, "transport_processed_messages表创建完成")

      Log.i(TAG, "Transport数据库表创建成功")

    } catch (e: Exception) {
      Log.e(TAG, "创建Transport数据库表失败", e)
      throw e
    }
  }
} 