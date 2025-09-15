/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.coscomm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.coscomm.data.CosAccessInfo
import org.thoughtcrime.securesms.coscomm.data.CosResult
import org.thoughtcrime.securesms.coscomm.manager.SubAccountPoolManager
import org.thoughtcrime.securesms.coscomm.service.CosPollingService

/**
 * SubAccount和轮询机制修复验证测试
 */
@RunWith(AndroidJUnit4::class)
class SubAccountFixValidationTest {
    
    private lateinit var context: Context
    private lateinit var subAccountPoolManager: SubAccountPoolManager
    private lateinit var cosPollingService: CosPollingService
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        subAccountPoolManager = SubAccountPoolManager.getInstance(context)
        cosPollingService = CosPollingService(context)
        
        // 清理测试环境
        subAccountPoolManager.clearAllData()
        cosPollingService.clearProcessingState()
    }
    
    @After
    fun tearDown() {
        // 清理测试数据
        subAccountPoolManager.clearAllData()
        cosPollingService.clearProcessingState()
        cosPollingService.shutdown()
    }
    
    @Test
    fun testSubAccountPersistence() {
        // 测试SubAccount数据持久化
        val recipientId = "test_recipient_001"
        val accessInfo = createTestAccessInfo()
        
        // 添加子账户
        val addResult = subAccountPoolManager.addReceivedSubAccount(recipientId, accessInfo)
        assertTrue("添加子账户应该成功", addResult is CosResult.Success)
        
        // 验证子账户存在
        val retrievedAccount = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
        assertNotNull("应该能够检索到子账户", retrievedAccount)
        assertEquals("子账户ID应该匹配", recipientId, retrievedAccount?.recipientId)
        
        // 获取统计信息
        val stats = subAccountPoolManager.getStatistics()
        assertEquals("应该有1个接收子账户", 1, stats.totalReceivedSubAccounts)
        assertEquals("应该有1个活跃接收子账户", 1, stats.activeReceivedSubAccounts)
    }
    
    @Test
    fun testSubAccountValidation() {
        // 测试凭证验证机制
        val recipientId = "test_recipient_002"
        
        // 测试无效凭证
        val invalidAccessInfo = CosAccessInfo(
            provider = "tencent",
            region = "ap-beijing",
            bucketName = "test-bucket",
            accessKeyId = "", // 空的访问密钥
            secretAccessKey = "test-secret",
            sessionToken = null,
            expireTime = Long.MAX_VALUE,
            sharedDirectory = "test-dir"
        )
        
        val invalidResult = subAccountPoolManager.addReceivedSubAccount(recipientId, invalidAccessInfo)
        assertTrue("无效凭证应该被拒绝", invalidResult is CosResult.Error)
        
        // 测试有效凭证
        val validAccessInfo = createTestAccessInfo()
        val validResult = subAccountPoolManager.addReceivedSubAccount(recipientId, validAccessInfo)
        assertTrue("有效凭证应该被接受", validResult is CosResult.Success)
    }
    
    @Test
    fun testPollingServiceDeduplication() {
        // 测试消息去重机制
        val pollingStatus = cosPollingService.getPollingStatus()
        assertFalse("轮询服务初始状态应该是停止的", pollingStatus.isRunning)
        assertEquals("初始处理消息数应该为0", 0, pollingStatus.processedMessageCount)
        
        // 获取去重统计信息
        val deduplicationStats = cosPollingService.getDeduplicationStatistics()
        assertEquals("初始处理消息总数应该为0", 0, deduplicationStats.totalProcessedMessages)
        assertEquals("初始缓存大小应该为0", 0, deduplicationStats.cacheSize)
    }
    
    @Test
    fun testPollingServiceStateManagement() {
        // 测试轮询服务状态管理
        assertFalse("轮询服务初始状态应该是停止的", cosPollingService.getPollingStatus().isRunning)
        
        // 启动轮询服务
        cosPollingService.startPolling()
        
        // 等待一小段时间让服务启动
        Thread.sleep(1000)
        
        val runningStatus = cosPollingService.getPollingStatus()
        assertTrue("轮询服务应该已启动", runningStatus.isRunning)
        assertTrue("轮询服务应该已初始化", runningStatus.isInitialized)
        
        // 停止轮询服务
        cosPollingService.stopPolling()
        
        val stoppedStatus = cosPollingService.getPollingStatus()
        assertFalse("轮询服务应该已停止", stoppedStatus.isRunning)
    }
    
    @Test
    fun testErrorHandling() {
        // 测试错误处理机制
        val recipientId = "test_recipient_003"
        val accessInfo = createTestAccessInfo()
        
        // 添加子账户
        subAccountPoolManager.addReceivedSubAccount(recipientId, accessInfo)
        
        // 模拟轮询错误
        val errorResult = subAccountPoolManager.incrementPollingErrors(recipientId)
        assertTrue("增加轮询错误应该成功", errorResult is CosResult.Success)
        
        // 验证错误计数
        val account = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
        assertNotNull("子账户应该仍然存在", account)
        assertEquals("错误计数应该为1", 1, account?.pollingErrors)
        
        // 重置错误计数
        val resetResult = subAccountPoolManager.resetPollingErrors(recipientId)
        assertTrue("重置轮询错误应该成功", resetResult is CosResult.Success)
        
        val resetAccount = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
        assertEquals("错误计数应该重置为0", 0, resetAccount?.pollingErrors)
    }
    
    @Test
    fun testCleanupMechanism() {
        // 测试清理机制
        val recipientId = "test_recipient_004"
        val accessInfo = createTestAccessInfo()
        
        // 添加子账户
        subAccountPoolManager.addReceivedSubAccount(recipientId, accessInfo)
        
        // 验证子账户存在
        val initialStats = subAccountPoolManager.getStatistics()
        assertEquals("应该有1个子账户", 1, initialStats.totalReceivedSubAccounts)
        
        // 执行清理（这里不会清理有效的子账户）
        val cleanupResult = subAccountPoolManager.cleanExpiredSubAccounts()
        assertTrue("清理操作应该成功", cleanupResult is CosResult.Success)
        
        // 验证有效子账户未被清理
        val afterCleanupStats = subAccountPoolManager.getStatistics()
        assertEquals("有效子账户不应该被清理", 1, afterCleanupStats.totalReceivedSubAccounts)
    }
    
    /**
     * 创建测试用的访问信息
     */
    private fun createTestAccessInfo(): CosAccessInfo {
        return CosAccessInfo(
            provider = "tencent",
            region = "ap-beijing",
            bucketName = "test-bucket-${System.currentTimeMillis()}",
            accessKeyId = "test-access-key-${System.currentTimeMillis()}",
            secretAccessKey = "test-secret-key-${System.currentTimeMillis()}",
            sessionToken = null,
            expireTime = Long.MAX_VALUE, // 永久有效
            sharedDirectory = "v2-channels/test-channel-${System.currentTimeMillis()}"
        )
    }
}
