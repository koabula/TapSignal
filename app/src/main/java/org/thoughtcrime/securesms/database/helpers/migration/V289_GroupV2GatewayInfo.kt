/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.tap.group.database.GroupV2StatusTable

@Suppress("ClassName")
object V289_GroupV2GatewayInfo : SignalDatabaseMigration {

  private val TAG = Log.tag(V289_GroupV2GatewayInfo::class)

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "为group_v2_status表添加Gateway字段")
    try {
      db.execSQL("ALTER TABLE ${GroupV2StatusTable.TABLE_NAME} ADD COLUMN member_gateways TEXT NOT NULL DEFAULT '{}' ")
      Log.d(TAG, "member_gateways列添加完成")
    } catch (e: Exception) {
      // 如果列已存在则忽略
      if (!e.message.orEmpty().contains("duplicate column", ignoreCase = true)) {
        Log.e(TAG, "添加member_gateways列失败", e)
        throw e
      } else {
        Log.w(TAG, "member_gateways列已存在，跳过本次迁移")
      }
    }
  }
}
