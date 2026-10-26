package com.buzzness.buzzlearn.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Debug variant only: the release `DefaultNetworkLogging` is a no-op and must not pull the
 * logging interceptor into the release classpath.
 *
 * OkHttp's default [HttpLoggingInterceptor.Logger] writes through `java.util.logging`, so a
 * JUL handler captures exactly what the debug interceptor would emit on a device.
 */
class DefaultNetworkLoggingTest {
    @Test
    fun installsBasicLevelLoggingInterceptor() {
        val client =
            OkHttpClient
                .Builder()
                .apply(DefaultNetworkLogging::install)
                .build()

        val loggers = client.interceptors.filterIsInstance<HttpLoggingInterceptor>()
        assertEquals(1, loggers.size)
        assertEquals(HttpLoggingInterceptor.Level.BASIC, loggers.single().level)
    }

    @Test
    fun basicLevelNeverLogsAuthorizationHeaderOrRequestResponseBodies() {
        val handler = CapturingHandler()
        val julLogger = Logger.getLogger(OkHttpClient::class.java.name)
        val previousLevel = julLogger.level
        val client =
            OkHttpClient
                .Builder()
                .apply(DefaultNetworkLogging::install)
                .addInterceptor(terminatingInterceptor())
                .build()
        val request =
            Request
                .Builder()
                .url("http://localhost/v1/auth/login")
                .header("Authorization", "Bearer super-secret-token")
                .post("request-secret-body".toRequestBody(JSON_MEDIA_TYPE))
                .build()

        try {
            julLogger.level = Level.ALL
            julLogger.addHandler(handler)
            client.newCall(request).execute().use { response ->
                response.body.string()
            }
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = previousLevel
        }

        val log = handler.messages.joinToString(separator = "\n")
        assertTrue("Expected request line in log, got:\n$log", log.contains("--> POST"))
        assertTrue("Expected response line in log, got:\n$log", log.contains("<-- 200"))
        assertFalse("Authorization token leaked into the log:\n$log", log.contains("super-secret-token"))
        assertFalse("Request body leaked into the log:\n$log", log.contains("request-secret-body"))
        assertFalse("Response body leaked into the log:\n$log", log.contains("response-secret-body"))
    }

    private fun terminatingInterceptor(): Interceptor =
        Interceptor { chain ->
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("response-secret-body".toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }

    private class CapturingHandler : Handler() {
        val messages = mutableListOf<String>()

        override fun publish(record: LogRecord) {
            messages += record.message
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
