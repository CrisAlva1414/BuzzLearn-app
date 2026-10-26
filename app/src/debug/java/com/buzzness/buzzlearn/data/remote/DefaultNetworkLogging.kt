package com.buzzness.buzzlearn.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Debug-only HTTP logging.
 *
 * BASIC never prints request/response bodies (login, register and refresh bodies carry
 * credentials/tokens). The Authorization header is redacted as defense in depth (RNF-14).
 */
object DefaultNetworkLogging : NetworkLogging {
    override fun install(builder: OkHttpClient.Builder) {
        val interceptor =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            }
        builder.addInterceptor(interceptor)
    }
}
