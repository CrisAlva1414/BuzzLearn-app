package com.buzzness.buzzlearn.data.remote

import com.buzzness.buzzlearn.data.remote.dto.AuthResponse
import com.buzzness.buzzlearn.data.remote.dto.LoginRequest
import com.buzzness.buzzlearn.data.remote.dto.LogoutRequest
import com.buzzness.buzzlearn.data.remote.dto.Me
import com.buzzness.buzzlearn.data.remote.dto.PasswordResetConfirmRequest
import com.buzzness.buzzlearn.data.remote.dto.PasswordResetRequest
import com.buzzness.buzzlearn.data.remote.dto.RefreshRequest
import com.buzzness.buzzlearn.data.remote.dto.RegisterRequest
import com.buzzness.buzzlearn.data.remote.dto.Tokens
import com.buzzness.buzzlearn.data.remote.dto.VerifyEmailRequest
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Auth endpoints from `openapi-v0.yaml` §auth. Only [logout] takes an explicit Authorization
 * header; the session layer owns every other token concern (S1c).
 */
interface AuthApi {
    @POST("v1/auth/register")
    suspend fun register(
        @Body body: RegisterRequest,
    ): AuthResponse

    @POST("v1/auth/verify-email")
    suspend fun verifyEmail(
        @Body body: VerifyEmailRequest,
    ): Me

    @POST("v1/auth/verify-email/resend")
    suspend fun resendVerification(): Unit

    @POST("v1/auth/login")
    suspend fun login(
        @Body body: LoginRequest,
    ): AuthResponse

    @POST("v1/auth/refresh")
    suspend fun refresh(
        @Body body: RefreshRequest,
    ): Tokens

    /**
     * Revokes the session on the server. Callers pass the explicit `"Bearer <token>"` header; the
     * session interceptor does not attach it because the `/v1/auth/` prefix is excluded.
     */
    @POST("v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
        @Body body: LogoutRequest,
    ): Unit

    @POST("v1/auth/password-reset/request")
    suspend fun passwordResetRequest(
        @Body body: PasswordResetRequest,
    ): Unit

    @POST("v1/auth/password-reset/confirm")
    suspend fun passwordResetConfirm(
        @Body body: PasswordResetConfirmRequest,
    ): Unit
}
