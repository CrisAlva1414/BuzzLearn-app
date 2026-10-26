package com.buzzness.buzzlearn.data.remote

import okhttp3.OkHttpClient

/**
 * Release builds never log HTTP traffic (the logging interceptor is not on the release
 * classpath).
 */
object DefaultNetworkLogging : NetworkLogging {
    override fun install(builder: OkHttpClient.Builder) = Unit
}
