package org.thoughtcrime.securesms.coscomm.data

/**
 * COS请求结果
 */
sealed class CosRequestResult {
    data class Success(val requestId: String, val channelId: String?) : CosRequestResult()
    data class Failure(val errorMessage: String) : CosRequestResult()
}

/**
 * 验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String?
)
