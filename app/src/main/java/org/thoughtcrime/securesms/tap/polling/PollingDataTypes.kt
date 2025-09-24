package org.thoughtcrime.securesms.tap.polling

/**
 * 轮询模块共享数据类型定义
 * 
 * 包含轮询系统中各个组件共享使用的数据类型定义。
 */

/**
 * 轮询执行结果
 */
data class PollingExecutionResult(
    val isSuccess: Boolean,
    val messagesFound: Int,
    val responseTime: Long,
    val error: String,
    val needsRetry: Boolean
) {
    companion object {
        fun success(messagesFound: Int, responseTime: Long) = PollingExecutionResult(
            isSuccess = true,
            messagesFound = messagesFound,
            responseTime = responseTime,
            error = "",
            needsRetry = false
        )
        
        fun failure(error: String, responseTime: Long, needsRetry: Boolean = false) = PollingExecutionResult(
            isSuccess = false,
            messagesFound = 0,
            responseTime = responseTime,
            error = error,
            needsRetry = needsRetry
        )
    }
}

/**
 * 文件轮询结果
 */
data class FilePollingResult(
    val isSuccess: Boolean,
    val processedFiles: Set<String>,
    val messagesFound: Int,
    val error: String,
    val needsRetry: Boolean
) {
    companion object {
        fun success(processedFiles: Set<String>, messagesFound: Int) = FilePollingResult(
            isSuccess = true,
            processedFiles = processedFiles,
            messagesFound = messagesFound,
            error = "",
            needsRetry = false
        )
        
        fun failure(error: String, needsRetry: Boolean = false) = FilePollingResult(
            isSuccess = false,
            processedFiles = emptySet(),
            messagesFound = 0,
            error = error,
            needsRetry = needsRetry
        )
    }
}

/**
 * 文件处理失败记录
 */
data class FileProcessingFailure(
    val fileName: String,
    val recipientId: String,
    val failureCount: Int,
    val lastFailureTime: Long,
    val lastError: String
) 