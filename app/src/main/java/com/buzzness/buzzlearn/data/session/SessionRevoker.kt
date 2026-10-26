package com.buzzness.buzzlearn.data.session

import com.buzzness.buzzlearn.data.remote.AuthApi
import com.buzzness.buzzlearn.data.remote.apiCall
import com.buzzness.buzzlearn.data.remote.dto.LogoutRequest

/**
 * Revokes the current session on the server (`POST /v1/auth/logout`).
 *
 * Implementations throw [com.buzzness.buzzlearn.data.remote.ApiException] for non-2xx responses
 * (so `invalid_token` can be treated as "already revoked") and let [java.io.IOException] and
 * [kotlinx.serialization.SerializationException] propagate.
 */
interface SessionRevoker {
    suspend fun revoke(
        accessToken: String,
        refreshToken: String,
    )
}

/**
 * [SessionRevoker] backed by [AuthApi.logout].
 *
 * [authApi] must be built on a client **without** the session interceptor and authenticator: the
 * explicit `Bearer <accessToken>` header is then the only Authorization header, and an expired
 * access token can never be refreshed implicitly while signing out (ADR-001).
 */
class AuthApiSessionRevoker(
    private val authApi: AuthApi,
) : SessionRevoker {
    override suspend fun revoke(
        accessToken: String,
        refreshToken: String,
    ) {
        apiCall {
            authApi.logout(
                authorization = "Bearer $accessToken",
                body = LogoutRequest(refreshToken),
            )
        }
    }
}
