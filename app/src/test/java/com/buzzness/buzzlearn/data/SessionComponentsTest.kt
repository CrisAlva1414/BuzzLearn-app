package com.buzzness.buzzlearn.data

import com.buzzness.buzzlearn.data.remote.ApiException
import com.buzzness.buzzlearn.data.remote.NetworkLogging
import com.buzzness.buzzlearn.data.remote.apiCall
import com.buzzness.buzzlearn.data.remote.dto.PasswordResetConfirmRequest
import com.buzzness.buzzlearn.data.remote.dto.PasswordResetRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [SessionComponents]. No Robolectric: the class takes a plain directory and a
 * [CoroutineScope], so DataStore works on the regular JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionComponentsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val openScopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelOpenScopes() {
        openScopes.forEach { it.cancel() }
    }

    @Test
    fun clientsAndDispatchersAreDistinct() {
        val components = newComponents()

        val clients =
            setOf(
                components.sessionClient,
                components.publicClient,
                components.refreshClient,
                components.revokerClient,
            )
        assertEquals("Every client must be a distinct instance", 4, clients.size)

        val dispatchers =
            setOf(
                components.sessionClient.dispatcher,
                components.publicClient.dispatcher,
                components.refreshClient.dispatcher,
                components.revokerClient.dispatcher,
            )
        assertEquals("Every client must have its own dispatcher", 4, dispatchers.size)
    }

    @Test
    fun authApiIsBoundToThePublicClient() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.url.encodedPath) {
                                "/v1/auth/password-reset/request" ->
                                    MockResponse
                                        .Builder()
                                        .code(401)
                                        .setHeader("Content-Type", "application/json; charset=utf-8")
                                        .body("""{"error":{"code":"invalid_token"}}""")
                                        .build()
                                "/v1/auth/password-reset/confirm" ->
                                    MockResponse
                                        .Builder()
                                        .code(302)
                                        .setHeader("Location", "/v1/auth/verify-email/resend")
                                        .build()
                                "/v1/auth/verify-email/resend" -> MockResponse.Builder().code(204).build()
                                "/v1/auth/refresh" -> MockResponse.Builder().code(500).build()
                                else -> MockResponse.Builder().code(404).build()
                            }
                    }

                val components = newComponents(baseUrl = server.url("/").toString())

                val error =
                    runCatching {
                        apiCall {
                            components.authApi.passwordResetRequest(PasswordResetRequest(email = "user@example.com"))
                        }
                    }.exceptionOrNull()
                assertTrue("Expected the 401 to surface as an API error but got $error", error is ApiException)

                val request = server.takeRequest()
                assertEquals("/v1/auth/password-reset/request", request.url.encodedPath)
                assertNull(
                    "The public auth API must never attach an Authorization header",
                    request.headers["Authorization"],
                )
                assertEquals(
                    "A 401 on a public auth endpoint must not trigger a token refresh",
                    1,
                    server.requestCount,
                )

                val redirectError =
                    runCatching {
                        apiCall {
                            components.authApi.passwordResetConfirm(
                                PasswordResetConfirmRequest(
                                    email = "user@example.com",
                                    code = "482913",
                                    newPassword = "new-secret",
                                ),
                            )
                        }
                    }.exceptionOrNull()
                assertTrue("Expected the 302 to surface as an API error but got $redirectError", redirectError is ApiException)
                assertEquals(
                    "The public client must not follow redirects (the session client does)",
                    2,
                    server.requestCount,
                )
            } finally {
                server.close()
            }
        }

    @Test
    fun onlyTheSessionClientCarriesAuthentication() {
        val components = newComponents()

        listOf(components.publicClient, components.refreshClient, components.revokerClient).forEach { client ->
            assertTrue("Auxiliary clients must not run application interceptors", client.interceptors.isEmpty())
            assertSame("Auxiliary clients must not authenticate", Authenticator.NONE, client.authenticator)
        }

        assertTrue("The session client must attach the access token", components.sessionClient.interceptors.isNotEmpty())
        assertNotSame(
            "The session client must install the session authenticator",
            Authenticator.NONE,
            components.sessionClient.authenticator,
        )
    }

    @Test
    fun auxiliaryClientsDisableRedirectsAndSessionKeepsDefaults() {
        val components = newComponents()

        listOf(components.publicClient, components.refreshClient, components.revokerClient).forEach { client ->
            assertFalse("Auth endpoints must not follow redirects", client.followRedirects)
            assertFalse("Auth endpoints must not follow SSL redirects", client.followSslRedirects)
        }

        assertTrue("The session client keeps OkHttp's default redirect policy", components.sessionClient.followRedirects)
        assertTrue(
            "The session client keeps OkHttp's default SSL redirect policy",
            components.sessionClient.followSslRedirects,
        )
    }

    @Test
    fun parsesTheBaseUrl() {
        val components = newComponents()

        assertEquals("api.example.test", components.baseUrl.host)
    }

    @Test
    fun uppercaseHostIsNormalized() {
        val components = newComponents(baseUrl = "HTTPS://API.EXAMPLE.TEST/")

        assertEquals("api.example.test", components.baseUrl.host)
    }

    @Test
    fun tokenStorePersistsOnlyTheRefreshToken() =
        runTest {
            val sessionDir = File(temporaryFolder.root, "session")
            val components = newComponents(sessionDir = sessionDir)

            components.tokenStore.saveRefreshToken(REFRESH_TOKEN)

            val sessionFile = File(sessionDir, "datastore/session.preferences_pb")
            assertTrue("Expected preferences file at ${sessionFile.path}", sessionFile.isFile)
            assertEquals(REFRESH_TOKEN, components.tokenStore.refreshToken())

            val raw = sessionFile.readBytes().toString(Charsets.UTF_8)
            assertTrue("The refresh token must be persisted", raw.contains(REFRESH_TOKEN))
            assertFalse("The access token must never be persisted", raw.contains(ACCESS_TOKEN_PROBE))
        }

    @Test
    fun blankOrInvalidBaseUrlIsRejectedWithoutEchoingTheValue() {
        val sessionDir = File(temporaryFolder.root, "session")

        val blankError =
            runCatching {
                SessionComponents.create("", sessionDir, newScope(), logging = NoLogging)
            }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException but got $blankError", blankError is IllegalArgumentException)

        val invalidProbe = "not-a-valid-url-probe"
        val invalidError =
            runCatching {
                SessionComponents.create(invalidProbe, sessionDir, newScope(), logging = NoLogging)
            }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException but got $invalidError", invalidError is IllegalArgumentException)
        val message = invalidError?.message.orEmpty()
        assertTrue(message.contains("valid http(s) URL"))
        assertFalse("Validation message must not echo the base URL", message.contains(invalidProbe))

        val schemeProbe = "ftp://probe-host.invalid/"
        val schemeError =
            runCatching {
                SessionComponents.create(schemeProbe, sessionDir, newScope(), logging = NoLogging)
            }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException but got $schemeError", schemeError is IllegalArgumentException)
        assertFalse("Validation message must not echo the host", schemeError?.message.orEmpty().contains("probe-host"))
    }

    private fun newComponents(
        baseUrl: String = "https://api.example.test/",
        sessionDir: File = File(temporaryFolder.root, "session"),
    ): SessionComponents = SessionComponents.create(baseUrl, sessionDir, newScope(), logging = NoLogging)

    private fun newScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        openScopes += scope
        return scope
    }

    private object NoLogging : NetworkLogging {
        override fun install(builder: OkHttpClient.Builder) = Unit
    }

    private companion object {
        const val REFRESH_TOKEN = "probe-refresh-token-7a2d"
        const val ACCESS_TOKEN_PROBE = "probe-access-token-51d0"
    }
}
