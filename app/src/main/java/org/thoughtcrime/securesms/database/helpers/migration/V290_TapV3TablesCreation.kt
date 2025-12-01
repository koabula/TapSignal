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
 * 创建 Tap v3 相关的数据库表，包括通道管理和IPFS内容存储
 */
@Suppress("ClassName")
object V290_TapV3TablesCreation : SignalDatabaseMigration {

  private val TAG = Log.tag(V290_TapV3TablesCreation::class)

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    Log.i(TAG, "开始创建 Tap v3 数据库表...")

    try {
      // 创建 Tap v3 通道表
      Log.d(TAG, "创建 tap_v3_channels 表...")
      db.execSQL(TapV3ChannelTable.CREATE_TABLE)
      
      // 创建通道表索引
      for (index in TapV3ChannelTable.CREATE_INDEX) {
        db.execSQL(index)
      }
      Log.d(TAG, "tap_v3_channels 表创建完成")

      // 创建 IPFS 内容表
      Log.d(TAG, "创建 ipfs_content 表...")
      db.execSQL(IpfsContentTable.CREATE_TABLE)
      
      // 创建 IPFS 内容表索引
      for (index in IpfsContentTable.CREATE_INDEX) {
        db.execSQL(index)
      }
      Log.d(TAG, "ipfs_content 表创建完成")

      Log.i(TAG, "Tap v3 数据库表创建成功")

    } catch (e: Exception) {
      Log.e(TAG, "创建 Tap v3 数据库表失败", e)
      throw e
    }
  }
}
