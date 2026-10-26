package com.buzzness.buzzlearn.data

import com.buzzness.buzzlearn.data.remote.AuthApi
import com.buzzness.buzzlearn.data.remote.NetworkLogging
import com.buzzness.buzzlearn.data.remote.NetworkModule
import com.buzzness.buzzlearn.data.remote.defaultNetworkLogging
import com.buzzness.buzzlearn.data.session.AuthApiSessionRevoker
import com.buzzness.buzzlearn.data.session.AuthApiTokenRefresher
import com.buzzness.buzzlearn.data.session.SessionManager
import com.buzzness.buzzlearn.data.session.TokenStore
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File

/**
 * Session-related dependency graph built without Android references.
 *
 * The refresh, revoker and public clients are deliberately **separate** OkHttp instances:
 *
 * - The session client carries an `Authenticator`. OkHttp blocks a dispatcher slot while an
 *   `Authenticator` runs, so a refresh that reused the session client from inside that callback
 *   could exhaust the pool and deadlock (each waiting request holds a slot the refresh needs).
 * - The revoker uses an explicit `Authorization: Bearer` header. Reusing the session client would
 *   make its interceptor add a second Authorization header for the same request.
 * - Login, register and password-reset must never attach a session token, and a 401 there is a
 *   credential error, not an expired token.
 *
 * Each client also gets its own connection pool and dispatcher, so a hung refresh cannot starve
 * image loading or an in-flight API call. **Never** hand the session client to Coil or any other
 * third-party caller: every request through it would be able to trigger a token refresh.
 */
class SessionComponents private constructor(
    val authApi: AuthApi,
    val sessionManager: SessionManager,
    val sessionClient: OkHttpClient,
    val tokenStore: TokenStore,
    val baseUrl: HttpUrl,
    internal val refreshClient: OkHttpClient,
    internal val revokerClient: OkHttpClient,
    internal val publicClient: OkHttpClient,
) {
    companion object {
        fun create(
            baseUrl: String,
            sessionDir: File,
            scope: CoroutineScope,
            logging: NetworkLogging = defaultNetworkLogging(),
            clock: () -> Long = { System.currentTimeMillis() / 1000 },
        ): SessionComponents {
            // Reason only: the base URL can contain environment-specific hosts, so it is never
            // echoed in the exception (RNF-14).
            val httpUrl =
                requireNotNull(baseUrl.toHttpUrlOrNull()) {
                    "Session base URL must be a valid http(s) URL."
                }
            val normalizedBaseUrl = httpUrl.toString()

            val publicClient =
                NetworkModule
                    .createOkHttpClient(logging)
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            val refreshClient =
                NetworkModule
                    .createOkHttpClient(logging)
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            val revokerClient =
                NetworkModule
                    .createOkHttpClient(logging)
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

            val authApi = NetworkModule.createAuthApi(normalizedBaseUrl, publicClient)
            val refreshAuthApi = NetworkModule.createAuthApi(normalizedBaseUrl, refreshClient)
            val revokerAuthApi = NetworkModule.createAuthApi(normalizedBaseUrl, revokerClient)

            val tokenStore = TokenStore(sessionDir, scope)
            val sessionManager =
                SessionManager(
                    tokenStore = tokenStore,
                    tokenRefresher = AuthApiTokenRefresher(refreshAuthApi),
                    sessionRevoker = AuthApiSessionRevoker(revokerAuthApi),
                    apiHost = httpUrl.host,
                    clock = clock,
                )

            val sessionClient =
                NetworkModule
                    .createOkHttpClient(logging)
                    .newBuilder()
                    .addInterceptor(sessionManager.authInterceptor)
                    .authenticator(sessionManager.authenticator)
                    .build()

            return SessionComponents(
                authApi = authApi,
                sessionManager = sessionManager,
                sessionClient = sessionClient,
                tokenStore = tokenStore,
                baseUrl = httpUrl,
                refreshClient = refreshClient,
                revokerClient = revokerClient,
                publicClient = publicClient,
            )
        }
    }
}
