package com.buzzness.buzzlearn.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
) {
    override fun toString(): String =
        "Tokens(accessToken=${redact(accessToken)}, refreshToken=${redact(refreshToken)}, " +
            "expiresIn=$expiresIn)"
}

@Serializable
data class AuthResponse(
    val tokens: Tokens,
    val user: Me,
) {
    override fun toString(): String = "AuthResponse(tokens=$tokens, user=$user)"
}
