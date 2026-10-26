package com.buzzness.buzzlearn.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Contract error body: `{"error": {"code": "...", "details": {...}}}`.
 *
 * `code` stays a [String] here on purpose: an unknown future code must decode instead of
 * aborting, and [com.buzzness.buzzlearn.data.remote.ApiErrorCode] maps it afterwards.
 */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val details: Map<String, JsonElement>? = null,
)
