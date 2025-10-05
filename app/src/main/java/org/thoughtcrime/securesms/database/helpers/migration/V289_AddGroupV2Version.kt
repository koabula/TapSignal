/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SQLiteDatabase

/**
 * 为 group_v2_status 表添加 version 列以支持乐观锁
 */
@Suppress("ClassName")
object V289_AddGroupV2Version : SignalDatabaseMigration {

  private val TAG = Log.tag(V289_AddGroupV2Version::class)

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "开始添加群组V2版本号列...")

    try {
      // 添加 version 列，默认值为 0
      db.execSQL("ALTER TABLE group_v2_status ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
      
      Log.i(TAG, "群组V2版本号列添加成功")

    } catch (e: Exception) {
      Log.e(TAG, "添加群组V2版本号列失败", e)
      throw e
    }
  }
}
