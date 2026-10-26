package com.buzzness.buzzlearn.data.remote

import okhttp3.OkHttpClient

/**
 * Variant-specific HTTP logging hook.
 *
 * `HttpLoggingInterceptor` is a `debugImplementation` dependency, so `src/main` must not
 * reference it. [defaultNetworkLogging] is provided by `src/debug` (BASIC level, Authorization
 * redacted) and by `src/release` (no-op).
 */
interface NetworkLogging {
    fun install(builder: OkHttpClient.Builder)
}

fun defaultNetworkLogging(): NetworkLogging = DefaultNetworkLogging
