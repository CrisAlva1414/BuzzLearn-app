package com.buzzness.buzzlearn.data.session

import com.buzzness.buzzlearn.data.remote.AuthApi
import com.buzzness.buzzlearn.data.remote.apiCall
import com.buzzness.buzzlearn.data.remote.dto.RefreshRequest
import com.buzzness.buzzlearn.data.remote.dto.Tokens

/**
 * Exchanges a refresh token for a new token pair.
 *
 * It is an interface so [SessionManager] does not depend on Retrofit and so the error paths
 * (`ApiException`, `IOException`) can be exercised without a server.
 *
 * Implementations must throw [com.buzzness.buzzlearn.data.remote.ApiException] for non-2xx
 * responses (so `invalid_token` is distinguishable) and let [java.io.IOException] propagate.
 */
interface TokenRefresher {
    suspend fun refresh(
        refreshToken: String,
        timezone: String,
    ): Tokens
}

/**
 * [TokenRefresher] backed by [AuthApi.refresh].
 *
 * [authApi] must be created from a **dedicated** Retrofit/OkHttp stack without an
 * `Authenticator`. Reusing the session client would let a failed refresh recurse into itself and
 * turn one bad token into a refresh storm (plan D3).
 */
class AuthApiTokenRefresher(
    private val authApi: AuthApi,
) : TokenRefresher {
    override suspend fun refresh(
        refreshToken: String,
        timezone: String,
    ): Tokens =
        apiCall {
            authApi.refresh(
                RefreshRequest(
                    refreshToken = refreshToken,
                    timezone = timezone,
                ),
            )
        }
}
