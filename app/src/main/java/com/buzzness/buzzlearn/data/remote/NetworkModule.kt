package com.buzzness.buzzlearn.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * JSON configuration shared by Retrofit and the error parser.
 *
 * [JsonBuilder.exceptionsWithDebugInfo] stays off so decode failures never embed the raw
 * response body (tokens, PII) in their exception message (RNF-14).
 */
@OptIn(ExperimentalSerializationApi::class)
val NetworkJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = false
        exceptionsWithDebugInfo = false
    }

private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Builds the OkHttp/Retrofit stack. There is no telemetry SDK and no custom User-Agent.
 */
object NetworkModule {
    fun createOkHttpClient(logging: NetworkLogging = defaultNetworkLogging()): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .apply(logging::install)
            .build()

    fun createRetrofit(
        baseUrl: String,
        client: OkHttpClient = createOkHttpClient(),
    ): Retrofit {
        // Reason only: the base URL may contain environment-specific hostnames, so it is not
        // echoed in the runtime exception (RNF-14). Parsing first avoids Retrofit's own error,
        // which would include the raw value.
        val httpUrl =
            requireNotNull(baseUrl.toHttpUrlOrNull()) {
                "Retrofit base URL must be a valid http(s) URL."
            }
        require(baseUrl.endsWith("/")) {
            "Retrofit base URL must end with '/'."
        }
        return Retrofit
            .Builder()
            .baseUrl(httpUrl)
            .client(client)
            .addConverterFactory(NetworkJson.asConverterFactory(JsonMediaType))
            .build()
    }

    fun createAuthApi(
        baseUrl: String,
        client: OkHttpClient = createOkHttpClient(),
    ): AuthApi = createRetrofit(baseUrl, client).create(AuthApi::class.java)
}
