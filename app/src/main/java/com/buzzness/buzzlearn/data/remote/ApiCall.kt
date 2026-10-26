package com.buzzness.buzzlearn.data.remote

import com.buzzness.buzzlearn.data.remote.dto.ErrorEnvelope
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.HttpException

/** Upper bound for the error body read; anything larger is treated as invalid (UNKNOWN). */
private const val MAX_ERROR_BODY_BYTES = 64L * 1024L

/**
 * Runs an API call and maps non-2xx responses to [ApiException].
 *
 * Network failures ([java.io.IOException]) and cancellation are left untouched. A malformed
 * error body is never allowed to crash the caller: it maps to [ApiErrorCode.UNKNOWN].
 *
 * A body that fails to decode on a 2xx response is rethrown as a sanitized
 * [SerializationException]: the failure type is preserved but the raw response (tokens, PII)
 * never reaches the exception message (RNF-14).
 */
suspend fun <T> apiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (exception: HttpException) {
        throw exception.toApiException()
    } catch (exception: SerializationException) {
        throw exception.sanitized()
    }

private fun SerializationException.sanitized(): SerializationException =
    SerializationException("Malformed server response body (${this::class.java.simpleName}).")

private fun HttpException.toApiException(): ApiException {
    val envelope =
        runCatching {
            response()?.errorBody()?.use { body ->
                body
                    .readBoundedUtf8(MAX_ERROR_BODY_BYTES)
                    ?.let { json -> NetworkJson.decodeFromString<ErrorEnvelope>(json) }
            }
        }.getOrNull()

    return ApiException(
        httpStatus = code(),
        code = ApiErrorCode.fromWireValue(envelope?.error?.code),
        details = envelope?.error?.details,
        cause = this,
    )
}

/**
 * Reads at most [maxBytes] of the body and returns it as UTF-8, or null when the body exceeds
 * the bound. The read is incremental so an oversized or malicious body is never fully buffered.
 */
private fun ResponseBody.readBoundedUtf8(maxBytes: Long): String? {
    val buffer = Buffer()
    val source = source()
    var remaining = maxBytes

    while (remaining > 0) {
        val read = source.read(buffer, remaining)
        if (read == -1L) {
            return buffer.readUtf8()
        }
        remaining -= read
    }

    return if (source.exhausted()) buffer.readUtf8() else null
}
