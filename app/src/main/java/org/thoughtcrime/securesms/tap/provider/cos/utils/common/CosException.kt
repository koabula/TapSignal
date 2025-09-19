package org.thoughtcrime.securesms.tap.provider.cos.utils.common

/**
 * COS操作异常
 */
class CosException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * COS认证异常
 */
class CosAuthException(
    message: String,
    cause: Throwable? = null
) : CosException(message, cause)

/**
 * COS网络异常
 */
class CosNetworkException(
    message: String,
    cause: Throwable? = null
) : CosException(message, cause) 