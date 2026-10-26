package com.buzzness.buzzlearn.data.remote.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request DTOs can end up in logs or exception messages, so their [toString] must never expose
 * credentials, verification codes or PII (RNF-14).
 */
class AuthRequestsRedactionTest {
    @Test
    fun everyRequestDtoRedactsSensitiveValues() {
        requestDtos().forEach { dto ->
            val rendered = dto.toString()
            val dtoName = dto::class.simpleName

            SENSITIVE_VALUES.forEach { sensitive ->
                assertFalse("$dtoName.toString() leaked \"$sensitive\": $rendered", rendered.contains(sensitive))
            }
            assertTrue("$dtoName.toString() is missing the redaction marker: $rendered", rendered.contains(REDACTED))
        }
    }

    @Test
    fun registerRequestKeepsNonSensitiveFieldsReadable() {
        val rendered = REGISTER.toString()

        assertTrue(rendered.contains("nickname=learner"))
        assertTrue(rendered.contains("privacyAccepted=true"))
        assertTrue(rendered.contains("timezone=America/Santiago"))
    }

    @Test
    fun registerRequestEqualsAndCopyStillWork() {
        assertEquals(REGISTER, REGISTER.copy())
        assertEquals(REGISTER.hashCode(), REGISTER.copy().hashCode())

        val otherEmail = REGISTER.copy(email = "other@example.com")
        assertNotEquals(REGISTER, otherEmail)
        assertEquals("other@example.com", otherEmail.email)
        assertEquals(REGISTER.password, otherEmail.password)
    }

    private companion object {
        const val REDACTED = "<redacted>"

        // Every sensitive field gets its own probe: if one were accidentally shared, a leak in
        // one field could be masked by a redaction of another field with the same value.
        const val EMAIL = "probe-email@example.test"
        const val PASSWORD = "probe-password-4c1e"
        const val REFRESH_TOKEN = "probe-refresh-token-7a2d"
        const val CODE = "probe-code-482913"
        const val BIRTH_DATE = "2000-01-01"
        const val NEW_PASSWORD = "probe-new-password-9b3f"

        val SENSITIVE_VALUES = listOf(EMAIL, PASSWORD, REFRESH_TOKEN, CODE, BIRTH_DATE, NEW_PASSWORD)

        val REGISTER =
            RegisterRequest(
                email = EMAIL,
                password = PASSWORD,
                nickname = "learner",
                birthDate = BIRTH_DATE,
                privacyAccepted = true,
                timezone = "America/Santiago",
            )

        val LOGIN =
            LoginRequest(
                email = EMAIL,
                password = PASSWORD,
                timezone = "America/Santiago",
            )

        fun requestDtos(): List<Any> =
            listOf(
                REGISTER,
                LOGIN,
                RefreshRequest(refreshToken = REFRESH_TOKEN, timezone = "America/Santiago"),
                LogoutRequest(refreshToken = REFRESH_TOKEN),
                VerifyEmailRequest(code = CODE),
                PasswordResetRequest(email = EMAIL),
                PasswordResetConfirmRequest(
                    email = EMAIL,
                    code = CODE,
                    newPassword = NEW_PASSWORD,
                ),
            )
    }
}
