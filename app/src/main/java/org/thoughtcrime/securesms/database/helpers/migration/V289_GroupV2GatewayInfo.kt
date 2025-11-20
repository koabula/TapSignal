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
      val cursor = db.rawQuery(
        "PRAGMA table_info(${GroupV2StatusTable.TABLE_NAME})",
        null
      )
      
      var hasColumn = false
      cursor.use {
        val nameIndex = it.getColumnIndexOrThrow("name")
        while (it.moveToNext()) {
          val columnName = it.getString(nameIndex)
          if (columnName == "member_gateways") {
            hasColumn = true
            break
          }
        }
      }
      
      if (hasColumn) {
        Log.w(TAG, "member_gateways列已存在,跳过本次迁移")
        return
      }
      
      db.execSQL(
        "ALTER TABLE ${GroupV2StatusTable.TABLE_NAME} ADD COLUMN member_gateways TEXT NOT NULL DEFAULT '{}'"
      )
      Log.i(TAG, "member_gateways列添加完成")
      
    } catch (e: Exception) {
      Log.e(TAG, "添加member_gateways列失败", e)
      throw e
    }
  }
}
