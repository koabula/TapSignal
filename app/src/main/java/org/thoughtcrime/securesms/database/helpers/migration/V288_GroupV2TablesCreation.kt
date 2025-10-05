/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable

/**
 * 创建群组V2模式相关的数据库表
 * 
 * 包括：
 * - group_v2_status: 群组V2模式状态表
 * - transport_group_processed_messages: 群组消息去重表
 */
@Suppress("ClassName")
object V288_GroupV2TablesCreation : SignalDatabaseMigration {

  private val TAG = Log.tag(V288_GroupV2TablesCreation::class)

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "开始创建群组V2数据库表...")

    try {
      // 创建群组V2状态表
      Log.d(TAG, "创建group_v2_status表...")
      db.execSQL(GroupV2StatusTable.CREATE_TABLE)
      
      // 创建群组V2状态表索引
      for (index in GroupV2StatusTable.CREATE_INDEXES) {
        db.execSQL(index)
      }
      Log.d(TAG, "group_v2_status表创建完成")

      // 创建群组消息去重表
      Log.d(TAG, "创建transport_group_processed_messages表...")
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS transport_group_processed_messages (" +
          "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
          "duplication_key TEXT UNIQUE NOT NULL, " +
          "message_id TEXT NOT NULL, " +
          "sender_aci TEXT NOT NULL, " +
          "group_id TEXT NOT NULL, " +
          "timestamp INTEGER NOT NULL, " +
          "processed_at INTEGER NOT NULL, " +
          "polling_member_aci TEXT, " +
          "created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)" +
        ")"
      )
      
      // 创建群组消息去重表索引
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_group_processed_messages_key_idx ON transport_group_processed_messages (duplication_key)")
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_group_processed_messages_group_idx ON transport_group_processed_messages (group_id)")
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_group_processed_messages_sender_idx ON transport_group_processed_messages (sender_aci)")
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_group_processed_messages_timestamp_idx ON transport_group_processed_messages (processed_at)")
      db.execSQL("CREATE INDEX IF NOT EXISTS transport_group_processed_messages_message_idx ON transport_group_processed_messages (message_id)")
      Log.d(TAG, "transport_group_processed_messages表创建完成")

      Log.i(TAG, "群组V2数据库表创建成功")

    } catch (e: Exception) {
      Log.e(TAG, "创建群组V2数据库表失败", e)
      throw e
    }
  }
}
