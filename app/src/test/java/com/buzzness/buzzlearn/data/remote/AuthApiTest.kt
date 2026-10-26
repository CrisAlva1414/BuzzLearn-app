package com.buzzness.buzzlearn.data.remote

import com.buzzness.buzzlearn.data.remote.dto.LoginRequest
import com.buzzness.buzzlearn.data.remote.dto.LogoutRequest
import com.buzzness.buzzlearn.data.remote.dto.PasswordResetConfirmRequest
import com.buzzness.buzzlearn.data.remote.dto.PasswordResetRequest
import com.buzzness.buzzlearn.data.remote.dto.RefreshRequest
import com.buzzness.buzzlearn.data.remote.dto.RegisterRequest
import com.buzzness.buzzlearn.data.remote.dto.VerifyEmailRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class AuthApiTest {
    @Test
    fun allAuthEndpointsUseContractPathsAndOnlyLogoutCarriesAnExplicitAuthorizationHeader() =
        runTest {
            val interceptor =
                RecordingInterceptor { request ->
                    when (request.url.encodedPath) {
                        "/v1/auth/register", "/v1/auth/login" ->
                            response(request, 200, AUTH_RESPONSE_JSON)
                        "/v1/auth/verify-email" -> response(request, 200, ME_JSON)
                        "/v1/auth/refresh" -> response(request, 200, TOKENS_JSON)
                        "/v1/auth/password-reset/request" -> response(request, 202, "")
                        "/v1/auth/verify-email/resend",
                        "/v1/auth/logout",
                        "/v1/auth/password-reset/confirm",
                        -> response(request, 204, null)
                        else -> response(request, 404, null)
                    }
                }
            val api = apiWith(interceptor)

            api.register(REGISTER_REQUEST)
            api.verifyEmail(VerifyEmailRequest(code = "482913"))
            api.resendVerification()
            api.login(LOGIN_REQUEST)
            api.refresh(REFRESH_REQUEST)
            api.logout("Bearer access-1", LogoutRequest(refreshToken = "refresh-1"))
            api.passwordResetRequest(PasswordResetRequest(email = "user@example.com"))
            api.passwordResetConfirm(
                PasswordResetConfirmRequest(
                    email = "user@example.com",
                    code = "482913",
                    newPassword = "new-secret",
                ),
            )

            assertEquals(
                listOf(
                    "/v1/auth/register",
                    "/v1/auth/verify-email",
                    "/v1/auth/verify-email/resend",
                    "/v1/auth/login",
                    "/v1/auth/refresh",
                    "/v1/auth/logout",
                    "/v1/auth/password-reset/request",
                    "/v1/auth/password-reset/confirm",
                ),
                interceptor.requests.map { it.url.encodedPath },
            )
            assertTrue(interceptor.requests.all { it.method == "POST" })
            assertTrue(
                "Only logout carries an explicit header",
                interceptor.requests
                    .filterNot { it.url.encodedPath == "/v1/auth/logout" }
                    .all { it.header("Authorization") == null },
            )
            assertEquals(
                "Bearer access-1",
                interceptor.requests.single { it.url.encodedPath == "/v1/auth/logout" }.header("Authorization"),
            )
        }

    @Test
    fun registerSendsSnakeCaseBodyAndNoAuthorizationHeader() =
        runTest {
            val interceptor = RecordingInterceptor { response(it, 201, AUTH_RESPONSE_JSON) }
            val api = apiWith(interceptor)

            api.register(REGISTER_REQUEST)

            val request = interceptor.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/auth/register", request.url.encodedPath)
            assertNull(request.header("Authorization"))
            assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())

            val body = NetworkJson.parseToJsonElement(readBody(request)).jsonObject
            assertEquals(
                setOf(
                    "email",
                    "password",
                    "nickname",
                    "birth_date",
                    "privacy_accepted",
                    "timezone",
                ),
                body.keys,
            )
            assertEquals(
                "2010-01-02",
                body.getValue("birth_date").jsonPrimitive.content,
            )
            assertEquals(
                "true",
                body.getValue("privacy_accepted").jsonPrimitive.content,
            )
        }

    @Test
    fun loginAndRefreshCarryTimezoneInTheBodyNotInAHeader() =
        runTest {
            val interceptor =
                RecordingInterceptor { request ->
                    when (request.url.encodedPath) {
                        "/v1/auth/login" -> response(request, 200, AUTH_RESPONSE_JSON)
                        "/v1/auth/refresh" -> response(request, 200, TOKENS_JSON)
                        else -> response(request, 404, null)
                    }
                }
            val api = apiWith(interceptor)

            api.login(LOGIN_REQUEST)
            api.refresh(REFRESH_REQUEST)

            val login = interceptor.requests[0]
            val refresh = interceptor.requests[1]
            assertNull(login.header("X-Timezone"))
            assertNull(refresh.header("X-Timezone"))
            assertEquals(
                "America/Santiago",
                bodyObject(login).getValue("timezone").jsonPrimitive.content,
            )
            assertEquals(
                "America/Santiago",
                bodyObject(refresh).getValue("timezone").jsonPrimitive.content,
            )
            assertEquals(
                "refresh-1",
                bodyObject(refresh).getValue("refresh_token").jsonPrimitive.content,
            )
        }

    @Test
    fun logoutAndPasswordResetBodiesMatchContract() =
        runTest {
            val interceptor =
                RecordingInterceptor { request ->
                    when (request.url.encodedPath) {
                        "/v1/auth/logout" -> response(request, 204, null)
                        "/v1/auth/password-reset/request" -> response(request, 202, "")
                        "/v1/auth/password-reset/confirm" -> response(request, 204, null)
                        else -> response(request, 404, null)
                    }
                }
            val api = apiWith(interceptor)

            api.logout("Bearer access-1", LogoutRequest(refreshToken = "refresh-1"))
            api.passwordResetRequest(PasswordResetRequest(email = "user@example.com"))
            api.passwordResetConfirm(
                PasswordResetConfirmRequest(
                    email = "user@example.com",
                    code = "482913",
                    newPassword = "new-secret",
                ),
            )

            assertEquals(
                setOf("refresh_token"),
                bodyObject(interceptor.requests[0]).keys,
            )
            assertEquals(
                setOf("email"),
                bodyObject(interceptor.requests[1]).keys,
            )
            val resetConfirm = bodyObject(interceptor.requests[2])
            assertEquals(
                setOf("email", "code", "new_password"),
                resetConfirm.keys,
            )
            assertEquals(
                "new-secret",
                resetConfirm.getValue("new_password").jsonPrimitive.content,
            )
        }

    @Test
    fun forbiddenResponseMapsToApiExceptionByErrorCode() =
        runTest {
            val apiError =
                apiErrorFor(
                    httpStatus = 403,
                    body = """{"error":{"code":"email_not_verified","details":{"field":"email"}}}""",
                )

            assertEquals(403, apiError.httpStatus)
            assertEquals(ApiErrorCode.EMAIL_NOT_VERIFIED, apiError.code)
            assertEquals(
                "email",
                apiError.details
                    ?.get("field")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun unauthorizedInvalidTokenMapsToInvalidTokenPreservingStatus() =
        runTest {
            val apiError = apiErrorFor(401, """{"error":{"code":"invalid_token"}}""")

            assertEquals(401, apiError.httpStatus)
            assertEquals(ApiErrorCode.INVALID_TOKEN, apiError.code)
            assertNull(apiError.details)
        }

    @Test
    fun forbiddenCodesAreNotCollapsedIntoASingleError() =
        runTest {
            val notVerified = apiErrorFor(403, """{"error":{"code":"email_not_verified"}}""")
            val lessonLocked = apiErrorFor(403, """{"error":{"code":"lesson_locked"}}""")

            assertEquals(ApiErrorCode.EMAIL_NOT_VERIFIED, notVerified.code)
            assertEquals(ApiErrorCode.LESSON_LOCKED, lessonLocked.code)
        }

    @Test
    fun unknownErrorCodeMapsToUnknown() =
        runTest {
            val apiError = apiErrorFor(500, """{"error":{"code":"future_code"}}""")

            assertEquals(500, apiError.httpStatus)
            assertEquals(ApiErrorCode.UNKNOWN, apiError.code)
        }

    @Test
    fun malformedErrorBodyMapsToUnknownWithoutCrashing() =
        runTest {
            val apiError = apiErrorFor(502, "<html>bad gateway</html>")

            assertEquals(502, apiError.httpStatus)
            assertEquals(ApiErrorCode.UNKNOWN, apiError.code)
            assertNull(apiError.details)
        }

    @Test
    fun missingErrorBodyMapsToUnknownWithoutCrashing() =
        runTest {
            val apiError = apiErrorFor(503, null)

            assertEquals(503, apiError.httpStatus)
            assertEquals(ApiErrorCode.UNKNOWN, apiError.code)
            assertNull(apiError.details)
        }

    @Test
    fun malformedSuccessBodyDoesNotLeakTokensOrRawJson() =
        runTest {
            val body =
                """{"tokens":{"access_token":"leaked-access-token","refresh_token":"leaked-refresh-token"}"""
            val api = apiWith(RecordingInterceptor { response(it, 200, body) })

            val error = runCatching { apiCall { api.login(LOGIN_REQUEST) } }.exceptionOrNull()

            assertTrue("Expected SerializationException but got $error", error is SerializationException)
            val chainMessages =
                generateSequence(error) { it.cause }
                    .mapNotNull { it.message }
                    .toList()
            assertTrue(
                "Expected a sanitized failure message, got: $chainMessages",
                chainMessages.any { it.startsWith("Malformed server response body") },
            )
            assertFalse(chainMessages.any { it.contains("leaked-access-token") })
            assertFalse(chainMessages.any { it.contains("leaked-refresh-token") })
            assertFalse(chainMessages.any { it.contains("\"access_token\"") })
            assertFalse(chainMessages.any { it.contains("\"refresh_token\"") })
        }

    @Test
    fun oversizedErrorBodyMapsToUnknownInsteadOfBeingFullyParsed() =
        runTest {
            val oversizedBody = """{"error":{"code":"invalid_token"}}""" + " ".repeat(64 * 1024)

            val apiError = apiErrorFor(500, oversizedBody)

            assertEquals(ApiErrorCode.UNKNOWN, apiError.code)
        }

    @Test
    fun errorBodyExactlyAtTheCapIsParsed() =
        runTest {
            val apiError = apiErrorFor(401, paddedErrorBody(MAX_ERROR_BODY_BYTES))

            assertEquals(401, apiError.httpStatus)
            assertEquals(ApiErrorCode.INVALID_TOKEN, apiError.code)
        }

    @Test
    fun errorBodyOneByteOverTheCapMapsToUnknown() =
        runTest {
            val apiError = apiErrorFor(401, paddedErrorBody(MAX_ERROR_BODY_BYTES + 1))

            assertEquals(401, apiError.httpStatus)
            assertEquals(ApiErrorCode.UNKNOWN, apiError.code)
        }

    @Test
    fun networkFailureStaysAnIoException() =
        runTest {
            val interceptor =
                object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): Response = throw IOException("offline")
                }
            val api = apiWith(interceptor)

            val error = runCatching { apiCall { api.login(LOGIN_REQUEST) } }.exceptionOrNull()

            assertTrue(error is IOException)
            assertFalse(error is ApiException)
        }

    private fun paddedErrorBody(size: Int): String {
        val json = """{"error":{"code":"invalid_token"}}"""
        return json + " ".repeat(size - json.toByteArray(Charsets.UTF_8).size)
    }

    private suspend fun apiErrorFor(
        httpStatus: Int,
        body: String?,
    ): ApiException {
        val interceptor = RecordingInterceptor { response(it, httpStatus, body) }
        val api = apiWith(interceptor)
        val error = runCatching { apiCall { api.login(LOGIN_REQUEST) } }.exceptionOrNull()
        assertTrue("Expected ApiException but got $error", error is ApiException)
        return error as ApiException
    }

    private fun apiWith(interceptor: Interceptor): AuthApi {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(interceptor)
                .build()
        return NetworkModule.createAuthApi(BASE_URL, client)
    }

    private fun response(
        request: Request,
        code: Int,
        body: String?,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply {
                if (body != null) {
                    body(body.toResponseBody(JSON_MEDIA_TYPE))
                }
            }.build()

    private fun readBody(request: Request): String {
        val builder = Buffer()
        request.body?.writeTo(builder)
        return builder.readUtf8()
    }

    private fun bodyObject(request: Request) = NetworkJson.parseToJsonElement(readBody(request)).jsonObject

    private class RecordingInterceptor(
        private val responseFactory: (Request) -> Response,
    ) : Interceptor {
        val requests = CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return responseFactory(request)
        }
    }

    private companion object {
        const val BASE_URL = "http://localhost/"
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val REGISTER_REQUEST =
            RegisterRequest(
                email = "user@example.com",
                password = "secret123",
                nickname = "learner",
                birthDate = "2010-01-02",
                privacyAccepted = true,
                timezone = "America/Santiago",
            )

        val LOGIN_REQUEST =
            LoginRequest(
                email = "user@example.com",
                password = "secret123",
                timezone = "America/Santiago",
            )

        val REFRESH_REQUEST =
            RefreshRequest(
                refreshToken = "refresh-1",
                timezone = "America/Santiago",
            )

        val ME_JSON =
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
            """.trimIndent()

        val TOKENS_JSON = """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":900}"""

        val AUTH_RESPONSE_JSON =
            """
            {
              "tokens": {"access_token":"access-1","refresh_token":"refresh-1","expires_in":900},
              "user": $ME_JSON
            }
            """.trimIndent()
    }
}
