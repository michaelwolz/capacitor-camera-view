package com.michaelwolz.capacitorcameraview.model

/**
 * A Result type for consistent error handling throughout camera operations.
 * Provides a functional approach to handling success and error cases.
 */
sealed class CameraResult<out T> {
    data class Success<T>(val value: T) : CameraResult<T>()
    data class Error(val exception: Exception) : CameraResult<Nothing>()

    /**
     * Transforms this result using the provided functions.
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (Exception) -> R
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Error -> onError(exception)
    }

    /**
     * Returns the value if Success, null otherwise.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Error -> null
    }

    /**
     * Returns the exception if Error, null otherwise.
     */
    fun exceptionOrNull(): Exception? = when (this) {
        is Success -> null
        is Error -> exception
    }

    /**
     * Returns true if this is a Success.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns true if this is an Error.
     */
    val isError: Boolean get() = this is Error
}
