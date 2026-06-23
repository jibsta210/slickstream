package com.slickstream.core.model

/** Minimal result wrapper used across repository boundaries. */
sealed interface DataResult<out T> {
    data class Success<T>(val data: T) : DataResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : DataResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.data
}

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(data))
    is DataResult.Error -> this
}

inline fun <T> runCatchingResult(block: () -> T): DataResult<T> = try {
    DataResult.Success(block())
} catch (t: Throwable) {
    DataResult.Error(t.message ?: "Unexpected error", t)
}
