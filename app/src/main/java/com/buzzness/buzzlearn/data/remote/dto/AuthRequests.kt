package com.buzzness.buzzlearn.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val nickname: String,
    @SerialName("birth_date") val birthDate: String,
    @SerialName("privacy_accepted") val privacyAccepted: Boolean,
    val timezone: String,
) {
    override fun toString(): String =
        "RegisterRequest(email=${redact(email)}, password=${redact(password)}, nickname=$nickname, " +
            "birthDate=${redact(birthDate)}, privacyAccepted=$privacyAccepted, timezone=$timezone)"
}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val timezone: String,
) {
    override fun toString(): String = "LoginRequest(email=${redact(email)}, password=${redact(password)}, timezone=$timezone)"
}

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    val timezone: String,
) {
    override fun toString(): String = "RefreshRequest(refreshToken=${redact(refreshToken)}, timezone=$timezone)"
}

@Serializable
data class LogoutRequest(
    @SerialName("refresh_token") val refreshToken: String,
) {
    override fun toString(): String = "LogoutRequest(refreshToken=${redact(refreshToken)})"
}

@Serializable
data class VerifyEmailRequest(
    val code: String,
) {
    override fun toString(): String = "VerifyEmailRequest(code=${redact(code)})"
}

@Serializable
data class PasswordResetRequest(
    val email: String,
) {
    override fun toString(): String = "PasswordResetRequest(email=${redact(email)})"
}

@Serializable
data class PasswordResetConfirmRequest(
    val email: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
) {
    override fun toString(): String =
        "PasswordResetConfirmRequest(email=${redact(email)}, code=${redact(code)}, " +
            "newPassword=${redact(newPassword)})"
}
