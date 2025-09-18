package org.thoughtcrime.securesms.tap.provider.cos.coscomm.debug

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.*
import org.thoughtcrime.securesms.tap.provider.cos.coscomm.data.*

/**
 * COS通道调试工具
 * 用于诊断COS v2模式的问题
 */
object CosChannelDebugTool {
    
    private val TAG = Log.tag(CosChannelDebugTool::class.java)
    
    /**
     * 生成完整的COS通道诊断报告
     */
    fun generateDiagnosticReport(context: Context, recipientId: String): String {
        val report = StringBuilder()
        
        report.appendLine("=== COS v2通道诊断报告 ===")
        report.appendLine("RecipientId: $recipientId")
        report.appendLine("时间: ${java.util.Date()}")
        report.appendLine()
        
        // 1. 通道状态检查
        report.appendLine("1. 通道状态检查:")
        val channelManager = CosChannelManager.getInstance(context)
        val channel = channelManager.getChannel(recipientId)
        
        if (channel == null) {
            report.appendLine("   ❌ 通道不存在")
        } else {
            report.appendLine("   ✅ 通道存在")
            report.appendLine("   - 通道ID: ${channel.channelId}")
            report.appendLine("   - 状态: ${channel.status}")
            report.appendLine("   - 建立时间: ${if (channel.establishedTime != null && channel.establishedTime > 0) java.util.Date(channel.establishedTime) else "未建立"}")
            report.appendLine("   - 最后活动: ${java.util.Date(channel.lastActivity)}")
            report.appendLine("   - 通道目录: ${channel.channelDirectory}")
            
            // 检查访问信息
            report.appendLine("   - 我的访问信息: ${if (channel.myAccessInfo != null) "✅ 存在" else "❌ 缺失"}")
            if (channel.myAccessInfo != null) {
                report.appendLine("     * 提供商: ${channel.myAccessInfo.provider}")
                report.appendLine("     * 区域: ${channel.myAccessInfo.region}")
                report.appendLine("     * 存储桶: ${channel.myAccessInfo.bucketName}")
                report.appendLine("     * 共享目录: ${channel.myAccessInfo.sharedDirectory}")
                report.appendLine("     * 过期时间: ${if (channel.myAccessInfo.expireTime == Long.MAX_VALUE) "永久" else java.util.Date(channel.myAccessInfo.expireTime)}")
            }
            
            report.appendLine("   - 对方访问信息: ${if (channel.theirAccessInfo != null) "✅ 存在" else "❌ 缺失"}")
            if (channel.theirAccessInfo != null) {
                report.appendLine("     * 提供商: ${channel.theirAccessInfo.provider}")
                report.appendLine("     * 区域: ${channel.theirAccessInfo.region}")
                report.appendLine("     * 存储桶: ${channel.theirAccessInfo.bucketName}")
                report.appendLine("     * 共享目录: ${channel.theirAccessInfo.sharedDirectory}")
                report.appendLine("     * 过期时间: ${if (channel.theirAccessInfo.expireTime == Long.MAX_VALUE) "永久" else java.util.Date(channel.theirAccessInfo.expireTime)}")
                report.appendLine("     * 是否过期: ${if (channel.theirAccessInfo.isExpired()) "❌ 已过期" else "✅ 有效"}")
            }
            
            // 检查通道能力
            report.appendLine("   - 是否活跃: ${if (channel.isActive()) "✅ 是" else "❌ 否"}")
            report.appendLine("   - 可发送消息: ${if (channel.canSendMessages()) "✅ 是" else "❌ 否"}")
            report.appendLine("   - 可接收消息: ${if (channel.canReceiveMessages()) "✅ 是" else "❌ 否"}")
        }
        
        report.appendLine()
        
        // 2. 子账户Pool检查
        report.appendLine("2. 子账户Pool检查:")
        val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
        val receivedSubAccount = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
        val sharedSubAccount = subAccountPoolManager.getValidSharedSubAccount(recipientId)

        report.appendLine("   - 接收的子账户: ${if (receivedSubAccount != null) "✅ 存在" else "❌ 缺失"}")
        if (receivedSubAccount != null) {
            report.appendLine("     * 访问密钥: ${receivedSubAccount.accessInfo.accessKeyId.take(8)}...")
            report.appendLine("     * 共享目录: ${receivedSubAccount.accessInfo.sharedDirectory}")
        }

        report.appendLine("   - 共享的子账户: ${if (sharedSubAccount != null) "✅ 存在" else "❌ 缺失"}")
        if (sharedSubAccount != null) {
            report.appendLine("     * 访问密钥: ${sharedSubAccount.accessInfo.accessKeyId.take(8)}...")
            report.appendLine("     * 共享目录: ${sharedSubAccount.accessInfo.sharedDirectory}")
        }
        
        report.appendLine()
        
        // 3. 消息发送检查
        report.appendLine("3. 消息发送检查:")
        val messageSendManager = CosMessageSendManager.getInstance(context)
        val recipientIdObj = try {
            org.thoughtcrime.securesms.recipients.RecipientId.from(recipientId)
        } catch (e: Exception) {
            null
        }
        
        if (recipientIdObj != null) {
            val shouldUseCos = messageSendManager.shouldUseCosForSending(recipientIdObj)
            report.appendLine("   - 应该使用COS发送: ${if (shouldUseCos) "✅ 是" else "❌ 否"}")
        } else {
            report.appendLine("   - ❌ 无效的RecipientId")
        }
        
        report.appendLine()
        
        // 4. 轮询服务检查
        report.appendLine("4. 轮询服务检查:")
        try {
            val pollingManager = CosPollingManager.getInstance(context)
            val managerStatus = pollingManager.getManagerStatus()
            val isRunning = managerStatus.pollingServiceStatus.isRunning
            report.appendLine("   - 轮询服务运行: ${if (isRunning) "✅ 是" else "❌ 否"}")
            report.appendLine("   - 管理器状态: ${managerStatus.managerState}")
            report.appendLine("   - 是否已初始化: ${if (managerStatus.isInitialized) "✅ 是" else "❌ 否"}")
            report.appendLine("   - 连续失败次数: ${managerStatus.consecutiveFailures}")

            val allEntries = subAccountPoolManager.getAllValidReceivedSubAccounts()
            report.appendLine("   - 可轮询的子账户条目数: ${allEntries.size}")
        } catch (e: Exception) {
            report.appendLine("   - ❌ 轮询服务检查失败: ${e.message}")
        }
        
        report.appendLine()
        
        // 5. 配置检查
        report.appendLine("5. 配置检查:")
        val configManager = CosSendConfigManager.getInstance(context)
        report.appendLine("   - COS发送启用: ${if (configManager.isCosSeendEnabled) "✅ 是" else "❌ 否"}")
        report.appendLine("   - 自动回退启用: ${if (configManager.isAutoFallbackEnabled) "✅ 是" else "❌ 否"}")
        
        val cosConfig = org.thoughtcrime.securesms.tap.provider.cos.cos.CosConfigStorage.getConfig(context)
        report.appendLine("   - COS配置存在: ${if (cosConfig != null) "✅ 是" else "❌ 否"}")
        if (cosConfig != null) {
            report.appendLine("     * 提供商: ${cosConfig.provider}")
            report.appendLine("     * 区域: ${cosConfig.region}")
            report.appendLine("     * 存储桶: ${cosConfig.bucketName}")
        }
        
        report.appendLine()
        
        // 6. 问题诊断和建议
        report.appendLine("6. 问题诊断和建议:")
        
        if (channel == null) {
            report.appendLine("   ❌ 主要问题: 通道不存在")
            report.appendLine("   💡 建议: 重新发起COS v2请求")
        } else {
            when {
                channel.status != ChannelStatus.ACTIVE -> {
                    report.appendLine("   ❌ 主要问题: 通道状态不是ACTIVE (当前: ${channel.status})")
                    report.appendLine("   💡 建议: 检查通道建立流程")
                }
                channel.myAccessInfo == null -> {
                    report.appendLine("   ❌ 主要问题: 缺少我的访问信息")
                    report.appendLine("   💡 建议: 重新生成访问凭证")
                }
                channel.theirAccessInfo == null -> {
                    report.appendLine("   ❌ 主要问题: 缺少对方的访问信息")
                    report.appendLine("   💡 建议: 等待对方接受请求或重新发送请求")
                }
                channel.theirAccessInfo?.isExpired() == true -> {
                    report.appendLine("   ❌ 主要问题: 对方的访问信息已过期")
                    report.appendLine("   💡 建议: 重新建立通道")
                }
                receivedSubAccount == null -> {
                    report.appendLine("   ❌ 主要问题: 子账户Pool中缺少对方的凭证")
                    report.appendLine("   💡 建议: 检查子账户Pool添加逻辑")
                }
                !configManager.isCosSeendEnabled -> {
                    report.appendLine("   ❌ 主要问题: COS发送被禁用")
                    report.appendLine("   💡 建议: 启用COS发送功能")
                }
                else -> {
                    report.appendLine("   ✅ 通道配置看起来正常")
                    report.appendLine("   💡 建议: 检查网络连接和COS服务可用性")
                }
            }
        }
        
        report.appendLine()
        report.appendLine("=== 诊断报告结束 ===")
        
        return report.toString()
    }
    
    /**
     * 打印诊断报告到日志
     */
    fun logDiagnosticReport(context: Context, recipientId: String) {
        val report = generateDiagnosticReport(context, recipientId)
        Log.i(TAG, "COS通道诊断报告:\n$report")
    }
    
    /**
     * 尝试修复常见问题
     */
    fun attemptAutoFix(context: Context, recipientId: String): String {
        val fixes = mutableListOf<String>()
        
        try {
            val channelManager = CosChannelManager.getInstance(context)
            val channel = channelManager.getChannel(recipientId)
            
            if (channel != null) {
                // 尝试重新建立通道
                if (channel.myAccessInfo != null && channel.theirAccessInfo != null && channel.status != ChannelStatus.ACTIVE) {
                    val established = channelManager.establishChannel(recipientId)
                    if (established) {
                        fixes.add("✅ 重新建立了通道")
                    }
                }
                
                // 尝试重新添加子账户到Pool
                if (channel.theirAccessInfo != null) {
                    val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
                    val result = subAccountPoolManager.addReceivedSubAccount(recipientId, channel.theirAccessInfo!!)
                    if (result.isSuccess()) {
                        fixes.add("✅ 重新添加了对方的子账户到Pool")
                    }
                }
                
                // 尝试启动轮询服务
                try {
                    val pollingManager = CosPollingManager.getInstance(context)
                    pollingManager.initialize()
                    val started = pollingManager.startPolling()
                    if (started) {
                        fixes.add("✅ 启动了轮询服务")
                    }
                } catch (e: Exception) {
                    fixes.add("❌ 启动轮询服务失败: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            fixes.add("❌ 自动修复失败: ${e.message}")
        }
        
        return if (fixes.isEmpty()) {
            "没有执行任何修复操作"
        } else {
            "执行的修复操作:\n${fixes.joinToString("\n")}"
        }
    }

    /**
     * 测试通道建立和轮询启动的完整流程
     */
    fun testChannelEstablishmentFlow(context: Context, recipientId: String): String {
        val report = StringBuilder()

        report.appendLine("=== 通道建立和轮询测试 ===")
        report.appendLine("RecipientId: $recipientId")
        report.appendLine()

        try {
            val channelManager = CosChannelManager.getInstance(context)
            val subAccountPoolManager = SubAccountPoolManager.getInstance(context)
            val pollingManager = CosPollingManager.getInstance(context)

            // 1. 检查通道状态
            val channel = channelManager.getChannel(recipientId)
            if (channel == null) {
                report.appendLine("❌ 通道不存在")
                return report.toString()
            }

            report.appendLine("1. 通道状态检查:")
            report.appendLine("   - 状态: ${channel.status}")
            report.appendLine("   - 我的访问信息: ${channel.myAccessInfo != null}")
            report.appendLine("   - 对方访问信息: ${channel.theirAccessInfo != null}")

            // 2. 尝试建立通道
            if (channel.status != ChannelStatus.ACTIVE) {
                report.appendLine("\n2. 尝试建立通道:")
                val established = channelManager.establishChannel(recipientId)
                report.appendLine("   - 建立结果: ${if (established) "✅ 成功" else "❌ 失败"}")
            } else {
                report.appendLine("\n2. 通道已经是ACTIVE状态")
            }

            // 3. 检查子账户Pool
            report.appendLine("\n3. 子账户Pool检查:")
            val receivedSubAccount = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
            report.appendLine("   - 接收的子账户: ${if (receivedSubAccount != null) "✅ 存在" else "❌ 缺失"}")

            if (receivedSubAccount == null && channel.theirAccessInfo != null) {
                report.appendLine("   - 尝试添加对方子账户到Pool:")
                val addResult = subAccountPoolManager.addReceivedSubAccount(recipientId, channel.theirAccessInfo!!)
                report.appendLine("     * 添加结果: ${if (addResult.isSuccess()) "✅ 成功" else "❌ 失败"}")
            }

            // 4. 检查轮询服务
            report.appendLine("\n4. 轮询服务检查:")
            val managerStatus = pollingManager.getManagerStatus()
            report.appendLine("   - 当前状态: ${managerStatus.managerState}")
            report.appendLine("   - 是否运行: ${managerStatus.pollingServiceStatus.isRunning}")

            val allEntries = subAccountPoolManager.getAllValidReceivedSubAccounts()
            report.appendLine("   - 可轮询条目数: ${allEntries.size}")

            if (!managerStatus.pollingServiceStatus.isRunning && allEntries.isNotEmpty()) {
                report.appendLine("   - 尝试启动轮询服务:")
                pollingManager.initialize()
                val startResult = pollingManager.startPolling()
                report.appendLine("     * 启动结果: ${if (startResult) "✅ 成功" else "❌ 失败"}")
            }

            // 5. 最终状态检查
            report.appendLine("\n5. 最终状态:")
            val finalChannel = channelManager.getChannel(recipientId)
            val finalSubAccount = subAccountPoolManager.getValidReceivedSubAccount(recipientId)
            val finalPollingStatus = pollingManager.getManagerStatus()

            report.appendLine("   - 通道状态: ${finalChannel?.status}")
            report.appendLine("   - 子账户可用: ${finalSubAccount != null}")
            report.appendLine("   - 轮询运行: ${finalPollingStatus.pollingServiceStatus.isRunning}")

            val allGood = finalChannel?.status == ChannelStatus.ACTIVE &&
                         finalSubAccount != null &&
                         finalPollingStatus.pollingServiceStatus.isRunning

            report.appendLine("   - 整体状态: ${if (allGood) "✅ 正常" else "❌ 有问题"}")

        } catch (e: Exception) {
            report.appendLine("❌ 测试过程中发生异常: ${e.message}")
            Log.e(TAG, "测试通道建立流程失败", e)
        }

        report.appendLine("\n=== 测试结束 ===")
        return report.toString()
    }
}
