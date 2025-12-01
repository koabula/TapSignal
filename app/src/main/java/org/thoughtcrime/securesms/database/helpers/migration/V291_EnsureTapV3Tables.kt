/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable
import org.thoughtcrime.securesms.tapv3.database.IpfsContentTable

/**
 * 确保 Tap v3 相关的数据库表存在
 * 这个迁移用于处理可能的表创建失败或遗漏情况
 */
@Suppress("ClassName")
object V291_EnsureTapV3Tables : SignalDatabaseMigration {

  private val TAG = Log.tag(V291_EnsureTapV3Tables::class.java)

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "确保 Tap v3 表存在...")

    try {
      // 检查 tap_v3_channels 表是否存在
      var channelsTableExists = false
      db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='tap_v3_channels'", null).use { cursor ->
        channelsTableExists = cursor.count > 0
      }

      if (!channelsTableExists) {
        Log.w(TAG, "tap_v3_channels 表不存在,现在创建...")
        db.execSQL(TapV3ChannelTable.CREATE_TABLE)
        for (index in TapV3ChannelTable.CREATE_INDEX) {
          db.execSQL(index)
        }
        Log.i(TAG, "tap_v3_channels 表创建成功")
      } else {
        Log.d(TAG, "tap_v3_channels 表已存在")
      }

      // 检查 ipfs_content 表是否存在
      var ipfsTableExists = false
      db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='tap_v3_ipfs_content'", null).use { cursor ->
        ipfsTableExists = cursor.count > 0
      }

      if (!ipfsTableExists) {
        Log.w(TAG, "tap_v3_ipfs_content 表不存在,现在创建...")
        db.execSQL(IpfsContentTable.CREATE_TABLE)
        for (index in IpfsContentTable.CREATE_INDEX) {
          db.execSQL(index)
        }
        Log.i(TAG, "tap_v3_ipfs_content 表创建成功")
      } else {
        Log.d(TAG, "tap_v3_ipfs_content 表已存在")
      }

      Log.i(TAG, "Tap v3 表检查完成")

    } catch (e: Exception) {
      Log.e(TAG, "确保 Tap v3 表存在时发生错误", e)
      throw e
    }
  }
}
