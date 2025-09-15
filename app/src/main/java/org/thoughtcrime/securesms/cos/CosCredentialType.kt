package org.thoughtcrime.securesms.cos

/**
 * COS凭证类型
 * 定义不同的凭证生成策略
 */
enum class CosCredentialType(
    val displayName: String,
    val description: String
) {
    /**
     * 自动选择
     * 根据请求类型自动选择最合适的凭证类型
     */
    AUTO(
        "自动选择",
        "根据请求类型自动选择最合适的凭证方式"
    ),
    
    /**
     * 临时凭证
     * 使用STS API生成临时访问凭证
     */
    TEMPORARY(
        "临时凭证",
        "使用STS API生成有时间限制的临时访问凭证"
    ),
    
    /**
     * 永久凭证
     * 使用子账号生成永久访问凭证
     */
    PERMANENT(
        "永久凭证",
        "创建子账号并生成永久访问密钥，避免频繁交换凭证"
    );
    
    /**
     * 是否支持真正的永久访问
     */
    fun supportsTruePermanentAccess(): Boolean {
        return this == PERMANENT || this == AUTO
    }
    
    /**
     * 是否需要定期刷新
     */
    fun requiresRefresh(): Boolean {
        return this == TEMPORARY
    }
    
    /**
     * 获取推荐的使用场景
     */
    fun getRecommendedUseCases(): List<String> {
        return when (this) {
            AUTO -> listOf(
                "推荐给大多数用户",
                "自动优化凭证策略",
                "平衡安全性和便利性"
            )
            TEMPORARY -> listOf(
                "高安全性要求",
                "短期通信",
                "测试和调试"
            )
            PERMANENT -> listOf(
                "长期通信",
                "避免频繁凭证交换",
                "稳定的通信关系"
            )
        }
    }
    
    /**
     * 获取安全性等级
     */
    fun getSecurityLevel(): SecurityLevel {
        return when (this) {
            AUTO -> SecurityLevel.BALANCED
            TEMPORARY -> SecurityLevel.HIGH
            PERMANENT -> SecurityLevel.MEDIUM
        }
    }
    
    /**
     * 获取便利性等级
     */
    fun getConvenienceLevel(): ConvenienceLevel {
        return when (this) {
            AUTO -> ConvenienceLevel.HIGH
            TEMPORARY -> ConvenienceLevel.LOW
            PERMANENT -> ConvenienceLevel.HIGH
        }
    }
}

/**
 * 安全性等级
 */
enum class SecurityLevel(val displayName: String) {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低"),
    BALANCED("平衡")
}

/**
 * 便利性等级
 */
enum class ConvenienceLevel(val displayName: String) {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低")
}
