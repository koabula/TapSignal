package org.thoughtcrime.securesms.tapv3

object TapV3Constants {
    const val VERSION = 3
    
    const val INLINE_THRESHOLD_BYTES = 2048
    const val PUSH_MESSAGE_MAX_SIZE = 4096
    const val MAX_ATTACHMENT_SIZE = 100L * 1024 * 1024
    
    const val IPFS_PIN_DURATION_DAYS_MESSAGE = 14
    const val IPFS_PIN_DURATION_DAYS_ATTACHMENT = 30
    const val IPFS_DOWNLOAD_TIMEOUT_MS = 30000L
    const val IPFS_UPLOAD_TIMEOUT_MS = 60000L
    
    const val KPUSH_KEY_SIZE_BYTES = 32
    const val KPUSH_KEY_VERSION_INITIAL = 1
    
    const val MAX_SEND_RETRIES = 3
    const val RETRY_BACKOFF_MS = 2000L
    
    const val IPFS_CACHE_SIZE_MB = 100
    const val IPFS_CACHE_MAX_AGE_DAYS = 7
    
    const val CID_LENGTH_MIN = 46
    const val CID_LENGTH_MAX = 59
}
