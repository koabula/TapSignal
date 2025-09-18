package org.thoughtcrime.securesms.tap.provider.cos.cos

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 轮询凭证验证工具
 * 验证轮询下载是否正确使用子账户永久凭证而不是STS临时凭证
 */
object PollingCredentialVerification {
    private val TAG = Log.tag(PollingCredentialVerification::class.java)
    
    /**
     * 验证轮询凭证的完整状态
     */
    fun verifyPollingCredentials(context: Context): PollingCredentialReport {
        Log.i(TAG, "开始验证轮询凭证状态...")
        
        return try {
            // 1. 检查配置状态
            val configStatus = checkConfigurationStatus(context)
            
            // 2. 检查CAM Pool状态
            val camPoolStatus = checkCamPoolStatus(context)
            
            // 3. 检查轮询服务状态
            val pollingStatus = checkPollingServiceStatus(context)
            
            // 4. 分析凭证类型分布
            val credentialAnalysis = analyzeCredentialTypes(context)
            
            // 5. 生成建议
            val recommendations = generateRecommendations(configStatus, camPoolStatus, credentialAnalysis)
            
            PollingCredentialReport(
                configStatus = configStatus,
                camPoolStatus = camPoolStatus,
                pollingStatus = pollingStatus,
                credentialAnalysis = credentialAnalysis,
                recommendations = recommendations,
                overallStatus = determineOverallStatus(configStatus, camPoolStatus, credentialAnalysis)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "验证轮询凭证状态失败", e)
            PollingCredentialReport.error("验证过程异常: ${e.message}")
        }
    }
    
    /**
     * 检查配置状态
     */
    private fun checkConfigurationStatus(context: Context): ConfigurationStatus {
        val credentialType = CosConfigStorage.getCredentialType(context)
        val isPermanentEnabled = CosConfigStorage.isPermanentCredentialEnabled(context)
        val cosConfig = CosConfigStorage.getConfig(context)
        
        return ConfigurationStatus(
            credentialType = credentialType,
            isPermanentEnabled = isPermanentEnabled,
            hasCosConfig = cosConfig != null,
            provider = cosConfig?.provider?.name ?: "未配置"
        )
    }
    
    /**
     * 检查子账户Pool状态
     */
    private fun checkCamPoolStatus(context: Context): CamPoolStatus {
        val subAccountPoolManager = org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.SubAccountPoolManager.getInstance(context)
        val allEntries = subAccountPoolManager.getAllValidReceivedSubAccounts()

        var permanentCount = 0
        var temporaryCount = 0
        var expiredCount = 0

        allEntries.forEach { entry ->
            if (entry.accessInfo.isExpired()) {
                expiredCount++
            } else if (entry.accessInfo.sessionToken.isNullOrEmpty()) {
                permanentCount++
            } else {
                temporaryCount++
            }
        }

        return CamPoolStatus(
            totalEntries = allEntries.size,
            permanentCredentials = permanentCount,
            temporaryCredentials = temporaryCount,
            expiredCredentials = expiredCount,
            activeEntries = allEntries.count { it.isActive }
        )
    }
    
    /**
     * 检查轮询服务状态
     */
    private fun checkPollingServiceStatus(context: Context): PollingServiceStatus {
        return try {
            val pollingService = org.thoughtcrime.securesms.tap.provider.cos.coscomm.service.CosPollingService(context)
            val status = pollingService.getPollingStatus()
            
            PollingServiceStatus(
                isRunning = status.isRunning,
                activeTaskCount = status.activeTaskCount,
                lastPollingTime = status.lastPollingTime,
                hasError = false,
                errorMessage = null
            )
        } catch (e: Exception) {
            PollingServiceStatus(
                isRunning = false,
                activeTaskCount = 0,
                lastPollingTime = 0,
                hasError = true,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 分析凭证类型分布
     */
    private fun analyzeCredentialTypes(context: Context): CredentialAnalysis {
        val messageService = org.thoughtcrime.securesms.tap.provider.cos.coscomm.manager.CosMessageService.getInstance(context)
        val statistics = messageService.getPollingCredentialStatistics()
        
        val totalEntries = statistics["totalEntries"] as Int
        val permanentCount = statistics["permanentCredentials"] as Int
        val temporaryCount = statistics["temporaryCredentials"] as Int
        
        val permanentPercentage = if (totalEntries > 0) (permanentCount * 100) / totalEntries else 0
        val isUsingSubAccounts = permanentCount > temporaryCount
        
        return CredentialAnalysis(
            totalCredentials = totalEntries,
            permanentCredentials = permanentCount,
            temporaryCredentials = temporaryCount,
            permanentPercentage = permanentPercentage,
            isUsingSubAccounts = isUsingSubAccounts,
            preferredType = if (isUsingSubAccounts) "子账户永久凭证" else "STS临时凭证"
        )
    }
    
    /**
     * 生成建议
     */
    private fun generateRecommendations(
        configStatus: ConfigurationStatus,
        camPoolStatus: CamPoolStatus,
        credentialAnalysis: CredentialAnalysis
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        // 配置建议
        if (!configStatus.isPermanentEnabled) {
            recommendations.add("建议启用永久凭证模式以避免STS时间限制")
        }
        
        if (configStatus.credentialType == CosCredentialType.TEMPORARY) {
            recommendations.add("当前使用临时凭证模式，建议切换到永久凭证模式")
        }
        
        // CAM Pool建议
        if (camPoolStatus.temporaryCredentials > camPoolStatus.permanentCredentials) {
            recommendations.add("检测到更多临时凭证，建议重新发送PERMANENT类型的COS请求")
        }
        
        if (camPoolStatus.expiredCredentials > 0) {
            recommendations.add("发现${camPoolStatus.expiredCredentials}个过期凭证，建议清理")
        }
        
        // 凭证类型建议
        if (!credentialAnalysis.isUsingSubAccounts) {
            recommendations.add("当前主要使用STS临时凭证，建议迁移到子账户永久凭证")
        }
        
        if (credentialAnalysis.permanentPercentage < 80) {
            recommendations.add("永久凭证占比较低(${credentialAnalysis.permanentPercentage}%)，建议提高到80%以上")
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("✅ 轮询凭证配置良好，正在使用子账户永久凭证")
        }
        
        return recommendations
    }
    
    /**
     * 确定总体状态
     */
    private fun determineOverallStatus(
        configStatus: ConfigurationStatus,
        camPoolStatus: CamPoolStatus,
        credentialAnalysis: CredentialAnalysis
    ): OverallStatus {
        val score = calculateScore(configStatus, camPoolStatus, credentialAnalysis)
        
        return when {
            score >= 80 -> OverallStatus.EXCELLENT
            score >= 60 -> OverallStatus.GOOD
            score >= 40 -> OverallStatus.FAIR
            else -> OverallStatus.POOR
        }
    }
    
    /**
     * 计算评分
     */
    private fun calculateScore(
        configStatus: ConfigurationStatus,
        camPoolStatus: CamPoolStatus,
        credentialAnalysis: CredentialAnalysis
    ): Int {
        var score = 0
        
        // 配置评分 (40分)
        if (configStatus.isPermanentEnabled) score += 20
        if (configStatus.credentialType == CosCredentialType.PERMANENT) score += 20
        
        // CAM Pool评分 (30分)
        if (camPoolStatus.permanentCredentials > camPoolStatus.temporaryCredentials) score += 20
        if (camPoolStatus.expiredCredentials == 0) score += 10
        
        // 凭证分析评分 (30分)
        if (credentialAnalysis.isUsingSubAccounts) score += 20
        score += (credentialAnalysis.permanentPercentage * 10) / 100
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * 生成详细报告
     */
    fun generateDetailedReport(report: PollingCredentialReport): String {
        val sb = StringBuilder()
        
        sb.appendLine("=== 轮询凭证验证报告 ===")
        sb.appendLine()
        
        // 总体状态
        sb.appendLine("📊 总体状态: ${report.overallStatus.displayName}")
        sb.appendLine()
        
        // 配置状态
        sb.appendLine("⚙️ 配置状态:")
        sb.appendLine("  凭证类型: ${report.configStatus.credentialType}")
        sb.appendLine("  永久凭证启用: ${if (report.configStatus.isPermanentEnabled) "✅ 是" else "❌ 否"}")
        sb.appendLine("  云服务提供商: ${report.configStatus.provider}")
        sb.appendLine()
        
        // CAM Pool状态
        sb.appendLine("🗄️ CAM Pool状态:")
        sb.appendLine("  总凭证数: ${report.camPoolStatus.totalEntries}")
        sb.appendLine("  永久凭证: ${report.camPoolStatus.permanentCredentials}")
        sb.appendLine("  临时凭证: ${report.camPoolStatus.temporaryCredentials}")
        sb.appendLine("  过期凭证: ${report.camPoolStatus.expiredCredentials}")
        sb.appendLine("  活跃条目: ${report.camPoolStatus.activeEntries}")
        sb.appendLine()
        
        // 凭证分析
        sb.appendLine("🔍 凭证分析:")
        sb.appendLine("  主要使用: ${report.credentialAnalysis.preferredType}")
        sb.appendLine("  永久凭证占比: ${report.credentialAnalysis.permanentPercentage}%")
        sb.appendLine("  是否使用子账户: ${if (report.credentialAnalysis.isUsingSubAccounts) "✅ 是" else "❌ 否"}")
        sb.appendLine()
        
        // 建议
        sb.appendLine("💡 改进建议:")
        report.recommendations.forEach { recommendation ->
            sb.appendLine("  • $recommendation")
        }
        
        return sb.toString()
    }
}

// 数据类定义
data class PollingCredentialReport(
    val configStatus: ConfigurationStatus,
    val camPoolStatus: CamPoolStatus,
    val pollingStatus: PollingServiceStatus,
    val credentialAnalysis: CredentialAnalysis,
    val recommendations: List<String>,
    val overallStatus: OverallStatus
) {
    companion object {
        fun error(message: String) = PollingCredentialReport(
            configStatus = ConfigurationStatus(CosCredentialType.AUTO, false, false, "错误"),
            camPoolStatus = CamPoolStatus(0, 0, 0, 0, 0),
            pollingStatus = PollingServiceStatus(false, 0, 0, true, message),
            credentialAnalysis = CredentialAnalysis(0, 0, 0, 0, false, "错误"),
            recommendations = listOf("修复验证过程中的错误"),
            overallStatus = OverallStatus.POOR
        )
    }
}

data class ConfigurationStatus(
    val credentialType: CosCredentialType,
    val isPermanentEnabled: Boolean,
    val hasCosConfig: Boolean,
    val provider: String
)

data class CamPoolStatus(
    val totalEntries: Int,
    val permanentCredentials: Int,
    val temporaryCredentials: Int,
    val expiredCredentials: Int,
    val activeEntries: Int
)

data class PollingServiceStatus(
    val isRunning: Boolean,
    val activeTaskCount: Int,
    val lastPollingTime: Long,
    val hasError: Boolean,
    val errorMessage: String?
)

data class CredentialAnalysis(
    val totalCredentials: Int,
    val permanentCredentials: Int,
    val temporaryCredentials: Int,
    val permanentPercentage: Int,
    val isUsingSubAccounts: Boolean,
    val preferredType: String
)

enum class OverallStatus(val displayName: String) {
    EXCELLENT("优秀 - 完全使用子账户永久凭证"),
    GOOD("良好 - 主要使用永久凭证"),
    FAIR("一般 - 混合使用凭证类型"),
    POOR("较差 - 主要使用临时凭证")
}
