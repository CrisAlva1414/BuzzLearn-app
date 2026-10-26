package com.buzzness.buzzlearn.data.remote

import kotlinx.serialization.json.JsonElement

/**
 * Typed representation of a non-2xx API response.
 *
 * The server message is intentionally not part of [message]: the API returns stable codes,
 * never user-facing text, and the response body may contain sensitive data (RNF-14).
 */
class ApiException(
    val httpStatus: Int,
    val code: ApiErrorCode,
    val details: Map<String, JsonElement>? = null,
    cause: Throwable? = null,
) : RuntimeException("HTTP $httpStatus (${code.wireValue})", cause)
