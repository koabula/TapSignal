package org.thoughtcrime.securesms.tapv3

sealed class TapV3Result<out T> {
    data class Success<T>(val data: T) : TapV3Result<T>()
    data class Failure(val error: TapV3Error, val message: String, val cause: Throwable? = null) : TapV3Result<Nothing>()
    
    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw TapV3Exception(error, message, cause)
    }
    
    fun onSuccess(action: (T) -> Unit): TapV3Result<T> {
        if (this is Success) {
            action(data)
        }
        return this
    }
    
    fun onFailure(action: (TapV3Error, String, Throwable?) -> Unit): TapV3Result<T> {
        if (this is Failure) {
            action(error, message, cause)
        }
        return this
    }
}

class TapV3Exception(
    val error: TapV3Error,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
