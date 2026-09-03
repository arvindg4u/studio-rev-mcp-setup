package com.adaptivesr.core

enum class ErrorCode {
  INVALID_RATING,
  ALREADY_PROCESSED,
  RATE_LIMITED,
  NOT_FOUND,
  NETWORK,
  UNKNOWN
}

sealed interface ApiResult<out T> {
  data class Ok<T>(val data: T) : ApiResult<T>
  data class Err(val code: ErrorCode, val msg: String? = null) : ApiResult<Nothing>
}
