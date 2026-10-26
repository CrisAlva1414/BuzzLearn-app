package com.buzzness.buzzlearn.data.session

import com.buzzness.buzzlearn.data.remote.ApiErrorCode
import com.buzzness.buzzlearn.data.remote.ApiException
import com.buzzness.buzzlearn.data.remote.NetworkJson
import com.buzzness.buzzlearn.data.remote.TimezoneProvider
import com.buzzness.buzzlearn.data.remote.dto.ErrorEnvelope
import com.buzzness.buzzlearn.data.remote.dto.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Owns the session and keeps the access token in memory only.
 *
 * Design decisions (ADR-001 session policy, plan D3):
 *
 * - The access token lives in this object and is lost on process death. Only the refresh token is
 *   persisted by [TokenStore].
 * - A 401 is ambiguous: a wrong password also returns 401 (`invalid_credentials`). The
 *   [authenticator] refreshes **only** when the response body says `invalid_token` and the path is
 *   not under `/v1/auth/`.
 * - Refreshes are single-flight: concurrent 401s for the same access token share one network call
 *   through [refreshMutex]. Waiters reuse the token produced by the winner.
 * - The rotated refresh token is persisted *before* the new access token becomes visible, so a
 *   crash between both steps can never leave a new access token paired with a stale refresh token.
 * - `invalid_token` on refresh clears the session; `403`, `IOException` and other errors keep it.
 *
 * Tokens are never logged and never included in exception messages (RNF-14).
 *
 * [refreshDispatcher] is only used by the synchronous [authenticator] callback. OkHttp invokes
 * `Authenticator` on a network thread that cannot suspend, so the refresh is bridged with
 * `runBlocking` on a dedicated dispatcher. The refresh call itself uses a separate OkHttp client
 * (see [AuthApiTokenRefresher]), which avoids blocking the connection that received the 401.
 *
 * [apiHost] is the host the session is allowed to authenticate against (host only, e.g.
 * `api.example.com`; S1d passes `HttpUrl(BuildConfig.BASE_URL).host`). It must not be blank: a
 * blank value throws [IllegalArgumentException]. It is normalized to lowercase because OkHttp
 * canonicalizes request hosts that way. Requests to any other host are proxied untouched and never
 * refreshed.
 */
class SessionManager(
    private val tokenStore: TokenStore,
    private val tokenRefresher: TokenRefresher,
    private val sessionRevoker: SessionRevoker,
    apiHost: String,
    private val timezoneProvider: TimezoneProvider = TimezoneProvider(),
    private val refreshDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    // Named differently from the constructor parameter so the anonymous interceptor below reads
    // this normalized copy instead of capturing the raw argument.
    private val normalizedApiHost: String =
        apiHost.trim().lowercase().also { normalized ->
            require(normalized.isNotEmpty()) { "apiHost must not be blank." }
        }
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = EVENT_BUFFER_SIZE)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val refreshMutex = Mutex()

    /**
     * Immutable snapshot so the request interceptor reads the token and its generation atomically.
     * Every mutation replaces the snapshot and bumps [generationCounter].
     */
    @Volatile
    private var accessTokenState: AccessTokenState? = null

    @Volatile
    private var generationCounter: Long = 0

    /** Attaches the in-memory access token, when present. Never needed for auth endpoints. */
    val authInterceptor: Interceptor =
        object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.host != normalizedApiHost || request.url.encodedPath.startsWith(AUTH_PATH_PREFIX)) {
                    return chain.proceed(request)
                }
                val snapshot = accessTokenState
                if (snapshot == null) {
                    return chain.proceed(request)
                }
                val authorized =
                    request
                        .newBuilder()
                        .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX${snapshot.token}")
                        .tag(SentToken::class.java, SentToken(snapshot.generation))
                        .build()
                return chain.proceed(authorized)
            }
        }

    /** Refreshes a 401 `invalid_token` once; everything else is left to the caller. */
    val authenticator: Authenticator =
        object : Authenticator {
            override fun authenticate(
                route: Route?,
                response: Response,
            ): Request? = onUnauthorized(response)
        }

    /** Returns the in-memory access token, or null when there is no session. */
    fun currentAccessToken(): String? = accessTokenState?.token

    /**
     * Persists the refresh token and starts an authenticated in-memory session.
     *
     * Used after login/register, whose responses carry both tokens. Validation failures never echo
     * the token value.
     */
    suspend fun signIn(tokens: Tokens) {
        refreshMutex.withLock {
            require(tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank()) {
                "Access and refresh tokens must not be blank."
            }
            check(applyTokens(tokens)) { "Could not persist the session tokens." }
        }
    }

    /**
     * Cold-start path: no access token survives process death, so a persisted refresh token means
     * "refresh now".
     *
     * - a session established while waiting for the lock (e.g. a concurrent sign-in): no-op;
     * - no (or blank) persisted token: [SessionState.Unauthenticated], no network call;
     * - `invalid_token`: clear storage and emit [SessionEvent.SignedOut];
     * - `IOException` / transient server errors: keep the persisted token and the current state
     *   ([SessionState.Unknown]) so a later retry can succeed.
     */
    suspend fun restoreSession() {
        refreshMutex.withLock {
            // A concurrent sign-in may have established the session while this restore was
            // waiting. Never let a cold-start restore overwrite or undo it.
            if (accessTokenState != null || _state.value == SessionState.Authenticated) {
                return@withLock
            }
            val storedToken =
                try {
                    tokenStore.refreshToken()
                } catch (exception: IOException) {
                    // Storage unavailable: keep [SessionState.Unknown] and let the caller retry. It
                    // is not evidence that the session is invalid, so it must not sign the user
                    // out.
                    return@withLock
                }
            if (storedToken.isNullOrBlank()) {
                markUnauthenticated()
                return@withLock
            }
            val tokens = fetchTokens(storedToken) ?: return@withLock
            applyTokens(tokens)
        }
    }

    /**
     * Explicit user sign-out: revokes the refresh token server-side (ADR-001) and then drops the
     * local session.
     *
     * - If the in-memory access token is missing or expired (within the 60 s skew), it is refreshed
     *   first because an expired token cannot authorise `POST /v1/auth/logout`. That refresh uses
     *   the dedicated refresher and never emits a second sign-out event.
     * - The access token travels explicitly as `Bearer <token>`: the `/v1/auth/` prefix is excluded
     *   from the session interceptor and authenticator, so no implicit refresh can run.
     * - The local clear always runs, even if the initial read or revocation throws an unmapped
     *   runtime exception or the coroutine is cancelled. It is executed inside [NonCancellable] so
     *   cancellation still propagates afterwards but cannot leave the token on disk.
     * - [SignOutResult.FAILED] means the persisted refresh token could not be deleted even after
     *   one retry. Memory is always cleared, but the token may resurrect on the next cold start.
     * - [SignOutResult.SUCCESS] requires either a confirmed revocation or a genuinely absent
     *   token. If the persisted token could not be read, durability cannot be established, so the
     *   result is [SignOutResult.LOCAL_ONLY].
     */
    suspend fun signOut(): SignOutResult =
        refreshMutex.withLock {
            var readFailed = false
            var revocationAttempted = false
            var revoked = false
            var cleared = false
            try {
                val refreshToken =
                    try {
                        tokenStore.refreshToken()
                    } catch (exception: CancellationException) {
                        // Cancellation is not a read failure: the finally still clears locally and
                        // the original CancellationException propagates once the block completes.
                        throw exception
                    } catch (exception: IOException) {
                        // Storage unavailable: there is nothing we can revoke reliably, but the
                        // local clear must still run and the result must not claim SUCCESS.
                        readFailed = true
                        null
                    } catch (exception: Exception) {
                        // Any other read failure (e.g. IllegalStateException) is also not evidence
                        // of revocation: clear locally and never report SUCCESS.
                        readFailed = true
                        null
                    }
                if (!refreshToken.isNullOrBlank()) {
                    revocationAttempted = true
                    val snapshot = accessTokenState
                    if (snapshot != null && snapshot.expiresAtEpochSeconds - EXPIRY_SKEW_SECONDS > clock()) {
                        revoked = revoke(snapshot.token, refreshToken)
                    } else {
                        val tokens = fetchTokens(refreshToken, clearOnInvalidToken = false)
                        if (tokens != null && tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank()) {
                            // The rotated pair is used solely for revocation: it is not installed
                            // because the session is ending. Installing it would publish
                            // [SessionState.Authenticated], persist a refresh token that is
                            // deleted immediately afterwards, and expose a signed-in state to the
                            // UI during sign-out. The server-issued pair is revoked even though it
                            // is never stored.
                            revoked = revoke(tokens.accessToken, tokens.refreshToken)
                        }
                    }
                }
            } finally {
                // Always drop the local session. NonCancellable lets the clear finish even when
                // this coroutine is already cancelling; the original exception still propagates
                // once the block completes.
                cleared =
                    withContext(NonCancellable) {
                        clearSession(SignOutReason.USER_INITIATED, retryOnFailure = true)
                    }
            }

            when {
                !cleared -> SignOutResult.FAILED
                readFailed -> SignOutResult.LOCAL_ONLY
                !revocationAttempted || revoked -> SignOutResult.SUCCESS
                else -> SignOutResult.LOCAL_ONLY
            }
        }

    private fun onUnauthorized(response: Response): Request? {
        if (response.code != HTTP_UNAUTHORIZED) return null
        val request = response.request
        // A 401 from another host is not ours to refresh: doing so would leak the refresh token
        // to a server that never received our access token.
        if (request.url.host != normalizedApiHost) return null
        // `/v1/auth/*` includes login (401 invalid_credentials) and refresh itself. Refreshing
        // there would either sign the user out on a typo or recurse.
        if (request.url.encodedPath.startsWith(AUTH_PATH_PREFIX)) return null
        if (response.priorResponse != null) return null
        if (request.tag(RetriedToken::class.java) != null) return null

        val sent = request.tag(SentToken::class.java) ?: return null
        val errorCode = readErrorCode(response) ?: return null
        if (errorCode != ApiErrorCode.INVALID_TOKEN) return null

        val newToken =
            runBlocking(refreshDispatcher) {
                refreshAfterUnauthorized(sent.generation)
            } ?: return null

        return request
            .newBuilder()
            .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$newToken")
            .tag(RetriedToken::class.java, RetriedToken)
            .build()
    }

    /**
     * Single-flight refresh. [sentGeneration] identifies the access token that received the 401.
     * If another request already rotated it while this one waited for the lock, reuse that token
     * instead of refreshing again.
     */
    private suspend fun refreshAfterUnauthorized(sentGeneration: Long): String? =
        refreshMutex.withLock {
            val snapshot = accessTokenState
            if (snapshot != null && snapshot.generation != sentGeneration) {
                return@withLock snapshot.token
            }
            if (snapshot == null && generationCounter != sentGeneration) {
                // The session was cleared while this request was in flight.
                return@withLock null
            }

            val refreshToken =
                try {
                    tokenStore.refreshToken()
                } catch (exception: IOException) {
                    // Storage unavailable: keep the session and let a later 401 retry.
                    return@withLock null
                }
            if (refreshToken.isNullOrBlank()) {
                clearSession(SignOutReason.INVALID_TOKEN)
                return@withLock null
            }
            val tokens = fetchTokens(refreshToken) ?: return@withLock null
            if (!applyTokens(tokens)) return@withLock null
            accessTokenState?.token
        }

    /**
     * Calls the refresh endpoint and maps its failures.
     *
     * Only `invalid_token` terminates the session. A `403` or a transport error leaves the stored
     * token untouched so the user is not signed out because a network blip.
     */
    private suspend fun fetchTokens(
        refreshToken: String,
        clearOnInvalidToken: Boolean = true,
    ): Tokens? =
        try {
            tokenRefresher.refresh(refreshToken, timezoneProvider.current())
        } catch (exception: ApiException) {
            if (exception.code == ApiErrorCode.INVALID_TOKEN && clearOnInvalidToken) {
                clearSession(SignOutReason.INVALID_TOKEN)
            }
            null
        } catch (exception: IOException) {
            null
        } catch (exception: SerializationException) {
            // A 200 with a malformed body must not escape the Authenticator or clear the session.
            null
        }

    /**
     * Calls the revoker and maps its failures to a boolean. `invalid_token` means the server
     * already invalidated the session, which counts as revoked.
     */
    private suspend fun revoke(
        accessToken: String,
        refreshToken: String,
    ): Boolean =
        try {
            sessionRevoker.revoke(accessToken, refreshToken)
            true
        } catch (exception: ApiException) {
            exception.code == ApiErrorCode.INVALID_TOKEN
        } catch (exception: IOException) {
            false
        } catch (exception: SerializationException) {
            false
        }

    /**
     * Persists the rotated refresh token **before** publishing the new access token, then bumps the
     * generation. Returns false when the server sent an unusable token pair.
     */
    private suspend fun applyTokens(tokens: Tokens): Boolean {
        if (tokens.accessToken.isBlank() || tokens.refreshToken.isBlank()) return false
        val persisted =
            try {
                tokenStore.saveRefreshToken(tokens.refreshToken)
                true
            } catch (exception: IllegalArgumentException) {
                false
            } catch (exception: IOException) {
                false
            }
        if (!persisted) return false

        generationCounter += 1
        accessTokenState =
            AccessTokenState(
                token = tokens.accessToken,
                generation = generationCounter,
                expiresAtEpochSeconds = clock() + tokens.expiresIn,
            )
        _state.value = SessionState.Authenticated
        return true
    }

    /**
     * Drops the in-memory session and the persisted refresh token, then publishes
     * [SessionState.Unauthenticated].
     *
     * The in-memory state is updated first so the final state is consistent even when storage
     * throws something other than [IOException]. [SessionEvent.SignedOut] is emitted only when the
     * persisted token was actually deleted; a failed delete leaves a documented residual token and
     * no event. Returns whether the persisted token was actually deleted, so [signOut] can
     * distinguish a durable sign-out from one that may resurrect on the next cold start.
     * [retryOnFailure] performs exactly one extra attempt when the first delete fails with
     * [IOException].
     */
    private suspend fun clearSession(
        reason: SignOutReason,
        retryOnFailure: Boolean = false,
    ): Boolean {
        generationCounter += 1
        accessTokenState = null
        _state.value = SessionState.Unauthenticated
        val cleared = clearPersistedToken(retryOnFailure)
        if (cleared) {
            _events.tryEmit(SessionEvent.SignedOut(reason))
        }
        return cleared
    }

    private suspend fun clearPersistedToken(retryOnFailure: Boolean): Boolean {
        try {
            tokenStore.clear()
            return true
        } catch (exception: CancellationException) {
            // Cancellation is never a clear failure: it must keep propagating.
            throw exception
        } catch (exception: IOException) {
            if (!retryOnFailure) return false
        } catch (exception: IllegalStateException) {
            // A store in an invalid state cannot be cleared; treat it as a failed delete.
            return false
        }
        return try {
            tokenStore.clear()
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            false
        } catch (exception: IllegalStateException) {
            false
        }
    }

    private fun markUnauthenticated() {
        generationCounter += 1
        accessTokenState = null
        _state.value = SessionState.Unauthenticated
    }

    /**
     * Peek (never consume) at most 64 KiB of the 401 body. A malformed, oversized or unknown body
     * maps to null, which means "do not refresh" rather than "retry blindly".
     */
    private fun readErrorCode(response: Response): ApiErrorCode? {
        val body = response.peekBody(MAX_ERROR_BODY_BYTES).string()
        val envelope =
            runCatching {
                NetworkJson.decodeFromString<ErrorEnvelope>(body)
            }.getOrNull()
        return envelope?.error?.code?.let(ApiErrorCode::fromWireValue)
    }

    private data class AccessTokenState(
        val token: String,
        val generation: Long,
        val expiresAtEpochSeconds: Long,
    ) {
        override fun toString(): String =
            "AccessTokenState(token=***, generation=$generation, expiresAtEpochSeconds=$expiresAtEpochSeconds)"
    }

    private class SentToken(
        val generation: Long,
    )

    private object RetriedToken

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val AUTH_PATH_PREFIX = "/v1/auth/"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        const val EVENT_BUFFER_SIZE = 16
        const val EXPIRY_SKEW_SECONDS = 60L
    }
}

/** Outcome of [SessionManager.signOut]. */
enum class SignOutResult {
    /** The server revoked the refresh token (or it was already invalid) and local state is gone. */
    SUCCESS,

    /** Local state is gone, but server-side revocation was not confirmed. */
    LOCAL_ONLY,

    /**
     * Local state could not be deleted even after one retry. Memory is always cleared, but the
     * persisted refresh token may resurrect on the next start.
     */
    FAILED,
}
