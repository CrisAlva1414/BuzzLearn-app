package com.buzzness.buzzlearn.data.remote.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Response DTOs are easy to log by accident (including nested in another [toString]), so their
 * string form must never reveal tokens or email (RNF-14).
 */
class AuthResponsesRedactionTest {
    @Test
    fun tokensRedactBothTokensButKeepExpiry() {
        val rendered = TOKENS.toString()

        assertFalse("Tokens.toString() leaked the access token: $rendered", rendered.contains(ACCESS_TOKEN))
        assertFalse("Tokens.toString() leaked the refresh token: $rendered", rendered.contains(REFRESH_TOKEN))
        assertTrue(rendered.contains("expiresIn=900"))
        assertEquals(2, rendered.redactionCount())
    }

    @Test
    fun meRedactsEmailButKeepsNonSensitiveFields() {
        val rendered = ME.toString()

        assertFalse("Me.toString() leaked the email: $rendered", rendered.contains(EMAIL))
        assertTrue(rendered.contains("id=7"))
        assertTrue(rendered.contains("nickname=learner"))
        assertEquals(1, rendered.redactionCount())
    }

    @Test
    fun authResponseRedactsNestedTokensAndEmail() {
        val rendered = AUTH_RESPONSE.toString()

        listOf(ACCESS_TOKEN, REFRESH_TOKEN, EMAIL).forEach { sensitive ->
            assertFalse("AuthResponse.toString() leaked a sensitive value: $rendered", rendered.contains(sensitive))
        }
        assertEquals(3, rendered.redactionCount())
    }

    private fun String.redactionCount(): Int = split(REDACTED).size - 1

    private companion object {
        const val REDACTED = "<redacted>"
        const val ACCESS_TOKEN = "probe-access-token-51d0"
        const val REFRESH_TOKEN = "probe-refresh-token-7a2d"
        const val EMAIL = "probe-user@example.test"

        val TOKENS = Tokens(accessToken = ACCESS_TOKEN, refreshToken = REFRESH_TOKEN, expiresIn = 900)

        val ME =
            Me(
                id = 7,
                email = EMAIL,
                nickname = "learner",
                emailVerified = true,
                dailyGoal = 20,
                hideFromRanking = false,
                onboardingCompleted = true,
                lastCourseId = null,
                stats =
                    Stats(
                        totalXp = 135,
                        level = 2,
                        levelXp = 85,
                        levelXpNeeded = 100,
                        currentStreak = 5,
                        longestStreak = 12,
                        streakFreezes = 1,
                        practicedToday = true,
                        today = DailyGoalStatus(earned = 15, goal = 20, met = false),
                    ),
            )

        val AUTH_RESPONSE = AuthResponse(tokens = TOKENS, user = ME)
    }
}
