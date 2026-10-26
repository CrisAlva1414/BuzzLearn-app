package com.buzzness.buzzlearn.data.remote.dto

import com.buzzness.buzzlearn.data.remote.NetworkJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthDtoRoundTripTest {
    @Test
    fun authResponseRoundTripsThroughEncodeAndDecode() {
        val decoded = NetworkJson.decodeFromString<AuthResponse>(AUTH_RESPONSE_JSON)

        val encoded = NetworkJson.encodeToString(decoded)
        val reDecoded = NetworkJson.decodeFromString<AuthResponse>(encoded)

        assertEquals(decoded, reDecoded)
    }

    @Test
    fun tokensRoundTripsThroughEncodeAndDecode() {
        val decoded = NetworkJson.decodeFromString<Tokens>(TOKENS_JSON)

        val encoded = NetworkJson.encodeToString(decoded)
        val reDecoded = NetworkJson.decodeFromString<Tokens>(encoded)

        assertEquals(decoded, reDecoded)
    }

    @Test
    fun meRoundTripsWhenOptionalLastCourseIdIsNull() {
        val decoded = NetworkJson.decodeFromString<Me>(ME_JSON)

        val encoded = NetworkJson.encodeToString(decoded)
        val reDecoded = NetworkJson.decodeFromString<Me>(encoded)

        assertEquals(decoded, reDecoded)
        assertNull(reDecoded.lastCourseId)
    }

    @Test
    fun meRoundTripsWhenOptionalLastCourseIdIsPresent() {
        val withCourse = ME_JSON.replace("\"last_course_id\": null", "\"last_course_id\": 42")
        val decoded = NetworkJson.decodeFromString<Me>(withCourse)

        val encoded = NetworkJson.encodeToString(decoded)
        val reDecoded = NetworkJson.decodeFromString<Me>(encoded)

        assertEquals(42L, decoded.lastCourseId)
        assertEquals(decoded, reDecoded)
    }

    private companion object {
        const val TOKENS_JSON =
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":900}"""

        const val ME_JSON =
            """
            {
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
              }
            }
            """

        const val AUTH_RESPONSE_JSON =
            """
            {
              "tokens": {"access_token":"access-1","refresh_token":"refresh-1","expires_in":900},
              "user": $ME_JSON
            }
            """
    }
}
