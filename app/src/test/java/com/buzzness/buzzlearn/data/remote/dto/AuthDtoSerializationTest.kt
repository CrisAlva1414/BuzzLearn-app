package com.buzzness.buzzlearn.data.remote.dto

import com.buzzness.buzzlearn.data.remote.ApiErrorCode
import com.buzzness.buzzlearn.data.remote.NetworkJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthDtoSerializationTest {
    @Test
    fun authResponseDecodesContractShapeAndIgnoresUnknownFields() {
        val json =
            """
            {
              "tokens": {
                "access_token": "access-1",
                "refresh_token": "refresh-1",
                "expires_in": 900
              },
              "user": {
                "id": 7,
                "email": "user@example.com",
                "nickname": "learner",
                "email_verified": true,
                "daily_goal": 20,
                "hide_from_ranking": false,
                "onboarding_completed": true,
                "last_course_id": null,
                "stats": {
                  "total_xp": 135,
                  "level": 2,
                  "level_xp": 85,
                  "level_xp_needed": 100,
                  "current_streak": 5,
                  "longest_streak": 12,
                  "streak_freezes": 1,
                  "practiced_today": true,
                  "today": { "earned": 15, "goal": 20, "met": false }
                },
                "future_user_field": "ignored"
              },
              "future_root_field": { "ignored": true }
            }
            """.trimIndent()

        val response = NetworkJson.decodeFromString<AuthResponse>(json)

        assertEquals("access-1", response.tokens.accessToken)
        assertEquals("refresh-1", response.tokens.refreshToken)
        assertEquals(900L, response.tokens.expiresIn)
        assertEquals(7L, response.user.id)
        assertEquals("user@example.com", response.user.email)
        assertEquals("learner", response.user.nickname)
        assertTrue(response.user.emailVerified)
        assertEquals(20, response.user.dailyGoal)
        assertEquals(false, response.user.hideFromRanking)
        assertEquals(null, response.user.lastCourseId)
        assertEquals(135, response.user.stats.totalXp)
        assertEquals(5, response.user.stats.currentStreak)
        assertEquals(15, response.user.stats.today.earned)
        assertEquals(false, response.user.stats.today.met)
    }

    @Test
    fun tokensDecodeSnakeCaseFields() {
        val json = """{"access_token":"a","refresh_token":"r","expires_in":60}"""

        val tokens = NetworkJson.decodeFromString<Tokens>(json)

        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(60L, tokens.expiresIn)
    }

    @Test
    fun registerRequestEncodesExactContractFields() {
        val request =
            RegisterRequest(
                email = "user@example.com",
                password = "secret123",
                nickname = "learner",
                birthDate = "2010-01-02",
                privacyAccepted = true,
                timezone = "America/Santiago",
            )

        val encoded = NetworkJson.parseToJsonElement(NetworkJson.encodeToString(request)).jsonObject

        assertEquals(
            setOf(
                "email",
                "password",
                "nickname",
                "birth_date",
                "privacy_accepted",
                "timezone",
            ),
            encoded.keys,
        )
        assertEquals("2010-01-02", encoded.getValue("birth_date").jsonPrimitive.content)
        assertTrue(encoded.getValue("privacy_accepted").jsonPrimitive.boolean)
    }

    @Test
    fun authRequestBodiesUseContractFieldNames() {
        val login =
            NetworkJson
                .parseToJsonElement(
                    NetworkJson.encodeToString(
                        LoginRequest(
                            email = "user@example.com",
                            password = "secret123",
                            timezone = "UTC",
                        ),
                    ),
                ).jsonObject
        assertEquals(setOf("email", "password", "timezone"), login.keys)

        val refresh =
            NetworkJson
                .parseToJsonElement(
                    NetworkJson.encodeToString(
                        RefreshRequest(refreshToken = "refresh-1", timezone = "UTC"),
                    ),
                ).jsonObject
        assertEquals(setOf("refresh_token", "timezone"), refresh.keys)

        val logout =
            NetworkJson
                .parseToJsonElement(
                    NetworkJson.encodeToString(LogoutRequest(refreshToken = "refresh-1")),
                ).jsonObject
        assertEquals(setOf("refresh_token"), logout.keys)

        val verify =
            NetworkJson
                .parseToJsonElement(
                    NetworkJson.encodeToString(VerifyEmailRequest(code = "482913")),
                ).jsonObject
        assertEquals(setOf("code"), verify.keys)

        val resetRequest =
            NetworkJson
                .parseToJsonElement(
                    NetworkJson.encodeToString(PasswordResetRequest(email = "user@example.com")),
                ).jsonObject
        assertEquals(setOf("email"), resetRequest.keys)

        val resetConfirm =
            NetworkJson
                .parseToJsonElement(
                    NetworkJson.encodeToString(
                        PasswordResetConfirmRequest(
                            email = "user@example.com",
                            code = "482913",
                            newPassword = "new-secret",
                        ),
                    ),
                ).jsonObject
        assertEquals(setOf("email", "code", "new_password"), resetConfirm.keys)
    }

    @Test
    fun errorEnvelopeDecodesCodeAndDetails() {
        val json =
            """
            {"error":{"code":"email_not_verified","details":{"field":"email"}}}
            """.trimIndent()

        val envelope = NetworkJson.decodeFromString<ErrorEnvelope>(json)

        assertEquals("email_not_verified", envelope.error.code)
        assertEquals(1, envelope.error.details?.size)
        assertEquals(
            ApiErrorCode.EMAIL_NOT_VERIFIED,
            ApiErrorCode.fromWireValue(envelope.error.code),
        )
    }

    @Test
    fun errorEnvelopeWithoutDetailsDecodes() {
        val envelope = NetworkJson.decodeFromString<ErrorEnvelope>("""{"error":{"code":"not_found"}}""")

        assertEquals("not_found", envelope.error.code)
        assertNull(envelope.error.details)
    }

    @Test
    fun unknownErrorCodeMapsToUnknown() {
        assertEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.fromWireValue("future_code"))
        assertEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.fromWireValue(null))
        assertEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.fromWireValue(""))
    }

    @Test
    fun allContractErrorCodesAreMapped() {
        val contractCodes =
            setOf(
                "validation_error",
                "email_already_registered",
                "nickname_taken",
                "underage",
                "privacy_not_accepted",
                "weak_password",
                "invalid_credentials",
                "invalid_token",
                "invalid_code",
                "code_expired",
                "email_not_verified",
                "too_many_requests",
                "not_found",
                "lesson_locked",
                "lesson_changed",
                "invalid_submission",
                "course_not_completed",
            )

        assertEquals(18, ApiErrorCode.entries.size)
        contractCodes.forEach { wireValue ->
            assertEquals(
                wireValue,
                ApiErrorCode.fromWireValue(wireValue).wireValue,
            )
        }
        assertEquals(ApiErrorCode.LESSON_LOCKED, ApiErrorCode.fromWireValue("lesson_locked"))
    }
}
