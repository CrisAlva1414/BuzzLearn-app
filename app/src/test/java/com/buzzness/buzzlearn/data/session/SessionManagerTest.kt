package com.buzzness.buzzlearn.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.buzzness.buzzlearn.data.remote.ApiErrorCode
import com.buzzness.buzzlearn.data.remote.ApiException
import com.buzzness.buzzlearn.data.remote.AuthApi
import com.buzzness.buzzlearn.data.remote.NetworkJson
import com.buzzness.buzzlearn.data.remote.NetworkModule
import com.buzzness.buzzlearn.data.remote.TimezoneProvider
import com.buzzness.buzzlearn.data.remote.apiCall
import com.buzzness.buzzlearn.data.remote.dto.LoginRequest
import com.buzzness.buzzlearn.data.remote.dto.RefreshRequest
import com.buzzness.buzzlearn.data.remote.dto.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.http.GET
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for [SessionManager]. Requests go through a real OkHttp stack against MockWebServer so
 * `Authenticator`/`Interceptor` ordering and single-flight behaviour are exercised end to end.
 */
class SessionManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val scopes = mutableListOf<CoroutineScope>()
    private var tokenStore: TokenStore? = null

    private val timezoneProvider =
        TimezoneProvider(
            systemZoneId = { "UTC" },
            availableZoneIds = { setOf("UTC") },
        )

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        scopes.forEach { scope -> scope.cancel() }
        server.close()
    }

    @Test
    fun concurrent401sRefreshOnceAndAllRetriesUseTheRotatedToken() =
        runBlocking {
            val refreshHits = AtomicInteger()
            val refreshTokens = CopyOnWriteArrayList<String>()
            val resourceAuthorization = CopyOnWriteArrayList<String?>()
            val newRefreshPersistedBeforeRetry = AtomicBoolean(true)
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        refreshTokens += refreshTokenFrom(request)
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    RESOURCE_PATH -> {
                        val authorization = request.headers[AUTHORIZATION]
                        resourceAuthorization += authorization
                        if (authorization == "$BEARER$NEW_ACCESS") {
                            val raw = sessionFile().readBytes().toString(Charsets.ISO_8859_1)
                            if (!raw.contains(NEW_REFRESH)) {
                                newRefreshPersistedBeforeRetry.set(false)
                            }
                            // Valid JSON string: the kotlinx.serialization converter backs ProbeApi.
                            jsonResponse(200, "\"ok\"")
                        } else {
                            jsonResponse(401, errorJson("invalid_token"))
                        }
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val api = probeApi(manager, BarrierInterceptor(parties = 3))

            val responses =
                coroutineScope {
                    (1..3)
                        .map { async(Dispatchers.IO) { apiCall { api.resource() } } }
                        .awaitAll()
                }

            assertEquals(listOf("ok", "ok", "ok"), responses)
            assertEquals("Exactly one refresh for three concurrent 401s", 1, refreshHits.get())
            assertEquals(listOf(OLD_REFRESH), refreshTokens)
            assertEquals(3, resourceAuthorization.count { it == "$BEARER$OLD_ACCESS" })
            assertEquals(3, resourceAuthorization.count { it == "$BEARER$NEW_ACCESS" })
            assertTrue(
                "The rotated refresh token must be persisted before any retry is sent",
                newRefreshPersistedBeforeRetry.get(),
            )
            assertEquals(NEW_REFRESH, sessionTokenStore().refreshToken())
            assertEquals(NEW_ACCESS, manager.currentAccessToken())
            assertEquals(SessionState.Authenticated, manager.state.value)
        }

    @Test
    fun refreshRotationStopsUsingTheOldRefreshToken() =
        runBlocking {
            val refreshTokens = CopyOnWriteArrayList<String>()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshTokens += refreshTokenFrom(request)
                        val index = refreshTokens.size
                        jsonResponse(200, tokensJson("access-$index", "refresh-$index"))
                    }
                    RESOURCE_PATH -> jsonResponse(401, errorJson("invalid_token"))
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            sessionTokenStore().saveRefreshToken(OLD_REFRESH)
            val firstManager = newManager()
            firstManager.restoreSession()
            assertEquals(listOf(OLD_REFRESH), refreshTokens)

            // A cold start on the same store must use the rotated token, not the original one.
            val secondManager = newManager()
            secondManager.restoreSession()
            assertEquals(listOf(OLD_REFRESH, "refresh-1"), refreshTokens)
            assertEquals("refresh-2", sessionTokenStore().refreshToken())
        }

    @Test
    fun refreshRejectedWithInvalidTokenClearsSessionAndEmitsSignOut() =
        runBlocking {
            val refreshHits = AtomicInteger()
            val resourceHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    RESOURCE_PATH -> {
                        resourceHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            val api = probeApi(manager)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                assertEquals(ApiErrorCode.INVALID_TOKEN, (error as ApiException).code)
                assertEquals("No retry after a failed refresh", 1, resourceHits.get())
                assertEquals(1, refreshHits.get())
                assertNull("Refresh token must be cleared", sessionTokenStore().refreshToken())
                assertNull(manager.currentAccessToken())
                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertTrue(
                    "Expected a SignedOut(INVALID_TOKEN) event, got ${events.received}",
                    events.received.contains(
                        SessionEvent.SignedOut(SignOutReason.INVALID_TOKEN),
                    ),
                )
                assertNoTokenInException(error)
            } finally {
                events.close()
            }
        }

    @Test
    fun concurrent401sWithInvalidRefreshTokenRefreshOnceAndSignOutOnce() =
        runBlocking {
            val refreshHits = AtomicInteger()
            val resourceHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    RESOURCE_PATH -> {
                        resourceHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            val api = probeApi(manager, BarrierInterceptor(parties = 3))

            val errors =
                coroutineScope {
                    (1..3)
                        .map { async(Dispatchers.IO) { runCatching { apiCall { api.resource() } }.exceptionOrNull() } }
                        .awaitAll()
                }

            try {
                assertEquals("One refresh for three concurrent 401s", 1, refreshHits.get())
                assertEquals("No retry after the refresh is rejected", 3, resourceHits.get())
                assertTrue(
                    "Every caller must see invalid_token: $errors",
                    errors.all { it is ApiException && it.code == ApiErrorCode.INVALID_TOKEN },
                )
                assertNull(sessionTokenStore().refreshToken())
                assertNull(manager.currentAccessToken())
                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertEquals(
                    "Concurrent 401s must emit a single sign-out: ${events.received}",
                    1,
                    events.received.count { it == SessionEvent.SignedOut(SignOutReason.INVALID_TOKEN) },
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun login401InvalidCredentialsNeverRefreshesNorSignsOut() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    LOGIN_PATH -> jsonResponse(401, errorJson("invalid_credentials"))
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            val events = collectEvents(this, manager)
            val authApi = authApiOnSessionClient(manager)

            val error =
                runCatching {
                    apiCall {
                        authApi.login(
                            LoginRequest(
                                email = "user@example.com",
                                password = "wrong-secret",
                                timezone = "UTC",
                            ),
                        )
                    }
                }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                assertEquals(ApiErrorCode.INVALID_CREDENTIALS, (error as ApiException).code)
                assertEquals(0, refreshHits.get())
                assertEquals(SessionState.Unknown, manager.state.value)
                assertTrue("A failed login must not emit SignedOut: ${events.received}", events.received.isEmpty())
                assertNull(sessionTokenStore().refreshToken())
            } finally {
                events.close()
            }
        }

    @Test
    fun refreshEndpoint401DoesNotRecurse() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            val authApi = authApiOnSessionClient(manager)

            val error =
                runCatching {
                    apiCall { authApi.refresh(RefreshRequest(OLD_REFRESH, "UTC")) }
                }.exceptionOrNull()

            assertTrue("Expected ApiException but got $error", error is ApiException)
            assertEquals(ApiErrorCode.INVALID_TOKEN, (error as ApiException).code)
            assertEquals("A 401 on /v1/auth/refresh must not trigger another refresh", 1, refreshHits.get())
        }

    @Test
    fun forbiddenEmailNotVerifiedDoesNotRefreshOrSignOut() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> jsonResponse(403, errorJson("email_not_verified"))
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            val api = probeApi(manager)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                assertEquals(ApiErrorCode.EMAIL_NOT_VERIFIED, (error as ApiException).code)
                assertEquals(0, refreshHits.get())
                assertEquals(SessionState.Authenticated, manager.state.value)
                assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
                assertTrue("A 403 must not emit SignedOut: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun unauthorizedWithANonInvalidTokenCodeIsNotRefreshed() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> jsonResponse(401, errorJson("invalid_credentials"))
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val api = probeApi(manager)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            assertTrue("Expected ApiException but got $error", error is ApiException)
            assertEquals(ApiErrorCode.INVALID_CREDENTIALS, (error as ApiException).code)
            assertEquals(0, refreshHits.get())
            assertEquals(SessionState.Authenticated, manager.state.value)
            assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
        }

    @Test
    fun malformedAndOversizedUnauthorizedBodyDoesNotTriggerRefresh() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    // Far above the 64 KiB bound used by SessionManager/ApiCall, and not JSON.
                    RESOURCE_PATH -> jsonResponse(401, "x".repeat(OVERSIZED_BODY_BYTES))
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            val api = probeApi(manager)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                // The bounded error-body read cannot parse it, so the code degrades to UNKNOWN.
                assertEquals(ApiErrorCode.UNKNOWN, (error as ApiException).code)
                assertEquals("An unparseable 401 body must not trigger a refresh", 0, refreshHits.get())
                assertEquals(SessionState.Authenticated, manager.state.value)
                assertEquals(OLD_ACCESS, manager.currentAccessToken())
                assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
                assertTrue("A malformed 401 must not sign out: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun transportFailureDuringRefreshKeepsTheSession() =
        runBlocking {
            dispatch { request ->
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> jsonResponse(401, errorJson("invalid_token"))
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager(refresher = IoFailingRefresher)
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            val api = probeApi(manager)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
                assertEquals(OLD_ACCESS, manager.currentAccessToken())
                assertEquals(SessionState.Authenticated, manager.state.value)
                assertTrue("IOException must not emit SignedOut: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun alreadyRetriedRequestGivesUpAfterASecond401() =
        runBlocking {
            val refreshHits = AtomicInteger()
            val resourceHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    RESOURCE_PATH -> {
                        resourceHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val api = probeApi(manager)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            assertTrue("Expected ApiException but got $error", error is ApiException)
            assertEquals(ApiErrorCode.INVALID_TOKEN, (error as ApiException).code)
            assertEquals("The retried 401 must not start another refresh", 1, refreshHits.get())
            assertEquals("Original request plus exactly one retry", 2, resourceHits.get())
            assertEquals(NEW_REFRESH, sessionTokenStore().refreshToken())
            assertEquals(SessionState.Authenticated, manager.state.value)
        }

    @Test
    fun restoreSessionAfterSignInIsANoOpAndNeverSignsOut() =
        runTest {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }
            val manager = newManager()
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))
            val events = collectEvents(this, manager)

            try {
                manager.restoreSession()

                assertEquals(
                    "A restore must not refresh over an established session",
                    0,
                    refreshHits.get(),
                )
                assertEquals(SessionState.Authenticated, manager.state.value)
                assertEquals(ACCESS_PROBE, manager.currentAccessToken())
                assertEquals(REFRESH_PROBE, sessionTokenStore().refreshToken())
                assertTrue(
                    "A restore after sign-in must not sign out: ${events.received}",
                    events.received.isEmpty(),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun authInterceptorOnlyAttachesToApiHostAndBusinessPaths() =
        runBlocking {
            val seen = CopyOnWriteArrayList<Triple<String, String, String?>>()
            dispatch { request ->
                seen += Triple(request.url.host, request.url.encodedPath, request.headers[AUTHORIZATION])
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> jsonResponse(200, "\"ok\"")
                    LOGIN_PATH -> jsonResponse(200, authResponseJson())
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))

            apiCall { probeApi(manager).resource() }
            apiCall {
                authApiOnSessionClient(manager).login(
                    LoginRequest(email = "user@example.com", password = "wrong-secret", timezone = "UTC"),
                )
            }
            // A real MockWebServer URL has a different host, so its request must stay unauthenticated.
            apiCall { probeApi(manager, baseUrl = server.url("/").toString()).resource() }

            val resource = seen.single { it.second == RESOURCE_PATH && it.first == API_HOST }
            assertEquals("$BEARER$OLD_ACCESS", resource.third)
            val login = seen.single { it.second == LOGIN_PATH }
            assertNull("Auth endpoints must not receive the bearer token", login.third)
            val foreign = seen.single { it.second == RESOURCE_PATH && it.first != API_HOST }
            assertNull("Foreign hosts must not receive the bearer token", foreign.third)
        }

    @Test
    fun unauthorizedFromForeignHostDoesNotRefreshOrSignOut() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> jsonResponse(401, errorJson("invalid_token"))
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            val api = probeApi(manager, baseUrl = server.url("/").toString())

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                assertEquals(ApiErrorCode.INVALID_TOKEN, (error as ApiException).code)
                assertEquals("A 401 from a foreign host must not refresh", 0, refreshHits.get())
                assertEquals(SessionState.Authenticated, manager.state.value)
                assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
                assertEquals(OLD_ACCESS, manager.currentAccessToken())
                assertTrue("A foreign 401 must not sign out: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun unauthorizedWithoutSentTokenTagDoesNotRefresh() =
        runBlocking {
            val refreshHits = AtomicInteger()
            dispatch { request ->
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> jsonResponse(401, errorJson("invalid_token"))
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            val manager = newManager()
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val events = collectEvents(this, manager)
            // No authInterceptor, so the request reaches the Authenticator without a SentToken tag.
            val client =
                OkHttpClient
                    .Builder()
                    .dns(apiHostDns())
                    .authenticator(manager.authenticator)
                    .build()
            val api = NetworkModule.createRetrofit(apiBaseUrl(), client).create(ProbeApi::class.java)

            val error = runCatching { apiCall { api.resource() } }.exceptionOrNull()

            try {
                assertTrue("Expected ApiException but got $error", error is ApiException)
                assertEquals(ApiErrorCode.INVALID_TOKEN, (error as ApiException).code)
                assertEquals("An untagged 401 must not refresh", 0, refreshHits.get())
                assertEquals(SessionState.Authenticated, manager.state.value)
                assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
                assertTrue("An untagged 401 must not sign out: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun restoreSessionWithMalformedRefreshBodyKeepsTokenAndStateUnknown() =
        runTest {
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> jsonResponse(200, "this-is-not-a-tokens-payload")
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }
            sessionTokenStore().saveRefreshToken(OLD_REFRESH)
            val manager = newManager()
            val events = collectEvents(this, manager)

            try {
                manager.restoreSession()

                assertEquals(SessionState.Unknown, manager.state.value)
                assertNull(manager.currentAccessToken())
                assertEquals(
                    "A malformed 200 must keep the persisted token",
                    OLD_REFRESH,
                    sessionTokenStore().refreshToken(),
                )
                assertTrue("A malformed 200 must not sign out: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun restoreSessionWithoutTokenIsUnauthenticatedAndMakesNoNetworkCall() =
        runTest {
            val manager = newManager()

            manager.restoreSession()

            assertEquals(SessionState.Unauthenticated, manager.state.value)
            assertNull(manager.currentAccessToken())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun restoreSessionWithValidTokenRefreshesAndAuthenticates() =
        runBlocking {
            val refreshTokens = CopyOnWriteArrayList<String>()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshTokens += refreshTokenFrom(request)
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            sessionTokenStore().saveRefreshToken(OLD_REFRESH)
            val manager = newManager()

            manager.restoreSession()

            assertEquals(SessionState.Authenticated, manager.state.value)
            assertEquals(NEW_ACCESS, manager.currentAccessToken())
            assertEquals(NEW_REFRESH, sessionTokenStore().refreshToken())
            assertEquals(listOf(OLD_REFRESH), refreshTokens)
        }

    @Test
    fun restoreSessionInvalidTokenClearsAndEmitsSignOut() =
        runBlocking {
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> jsonResponse(401, errorJson("invalid_token"))
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            sessionTokenStore().saveRefreshToken(OLD_REFRESH)
            val manager = newManager()
            val events = collectEvents(this, manager)

            try {
                manager.restoreSession()

                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertNull(sessionTokenStore().refreshToken())
                assertTrue(
                    "Expected SignedOut(INVALID_TOKEN), got ${events.received}",
                    events.received.contains(
                        SessionEvent.SignedOut(SignOutReason.INVALID_TOKEN),
                    ),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun restoreSessionTransportFailureKeepsTheTokenAndDoesNotSignOut() =
        runTest {
            sessionTokenStore().saveRefreshToken(OLD_REFRESH)
            val manager = newManager(refresher = IoFailingRefresher)
            val events = collectEvents(this, manager)

            try {
                manager.restoreSession()

                assertEquals(SessionState.Unknown, manager.state.value)
                assertEquals(OLD_REFRESH, sessionTokenStore().refreshToken())
                assertTrue("IOException must not sign out: ${events.received}", events.received.isEmpty())
            } finally {
                events.close()
            }
        }

    @Test
    fun restoreSessionWithBlankPersistedTokenIsUnauthenticated() =
        runTest {
            writeRawRefreshToken("   ")
            val manager = newManager()

            manager.restoreSession()

            assertEquals(SessionState.Unauthenticated, manager.state.value)
            assertNull(sessionTokenStore().refreshToken())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun restoreSessionWithCorruptPreferencesFileIsUnauthenticatedWithoutNetwork() =
        runTest {
            // A truncated/corrupt DataStore file means "no session": TokenStore replaces it with
            // empty preferences, so restore must not network, retry, or crash.
            sessionFile().parentFile?.mkdirs()
            sessionFile().writeBytes(CORRUPT_STORE_BYTES)
            assertTrue("Expected a corrupt preferences file on disk", sessionFile().isFile)

            val manager = newManager()
            val events = collectEvents(this, manager)

            try {
                manager.restoreSession()

                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertNull(manager.currentAccessToken())
                assertNull(sessionTokenStore().refreshToken())
                assertEquals(0, server.requestCount)
                assertTrue(
                    "A corrupt store is not an invalid-token sign-out: ${events.received}",
                    events.received.isEmpty(),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun accessTokenIsNeverPersisted() =
        runTest {
            val manager = newManager()

            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))

            val raw = sessionFile().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue("Expected the refresh token to be persisted", raw.contains(REFRESH_PROBE))
            assertFalse("The access token must never reach storage", raw.contains(ACCESS_PROBE))
            assertEquals(REFRESH_PROBE, sessionTokenStore().refreshToken())
            assertEquals(ACCESS_PROBE, manager.currentAccessToken())
            assertEquals(SessionState.Authenticated, manager.state.value)
        }

    @Test
    fun signOutRevokesCurrentTokensClearsStorageAndState() =
        runTest {
            val revoker = FakeSessionRevoker()
            val manager = newManager(revoker = revoker, clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))
            val events = collectEvents(this, manager)

            try {
                val result = manager.signOut()

                assertEquals(SignOutResult.SUCCESS, result)
                assertEquals(listOf(ACCESS_PROBE to REFRESH_PROBE), revoker.calls)
                assertNull(sessionTokenStore().refreshToken())
                assertNull(manager.currentAccessToken())
                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertTrue(
                    "Expected SignedOut(USER_INITIATED), got ${events.received}",
                    events.received.contains(
                        SessionEvent.SignedOut(SignOutReason.USER_INITIATED),
                    ),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun signOutWithExpiredAccessTokenRefreshesBeforeRevoking() =
        runBlocking {
            val refreshTokens = CopyOnWriteArrayList<String>()
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> {
                        refreshTokens += refreshTokenFrom(request)
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            var now = NOW
            val revoker = FakeSessionRevoker()
            val manager = newManager(revoker = revoker, clock = { now })
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            // Push the clock past the access token's lifetime minus the 60 s skew.
            now = NOW + EXPIRES_IN

            val result = manager.signOut()

            assertEquals(SignOutResult.SUCCESS, result)
            assertEquals(listOf(OLD_REFRESH), refreshTokens)
            assertEquals(listOf(NEW_ACCESS to NEW_REFRESH), revoker.calls)
            assertNull(sessionTokenStore().refreshToken())
            assertNull(manager.currentAccessToken())
            assertEquals(SessionState.Unauthenticated, manager.state.value)
        }

    @Test
    fun signOutWithExpiredAccessTokenNeverPublishesAuthenticated() =
        runBlocking {
            dispatch { request ->
                when (request.url.encodedPath) {
                    REFRESH_PATH -> jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            var now = NOW
            val revoker = FakeSessionRevoker()
            val manager = newManager(revoker = revoker, clock = { now })
            val states = CopyOnWriteArrayList<SessionState>()
            val collector =
                launch(Dispatchers.Unconfined) {
                    manager.state.collect { state -> states += state }
                }

            try {
                manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
                assertEquals(SessionState.Authenticated, manager.state.value)
                // The sign-in emission is the pre-existing state, not part of the sign-out
                // transition, so only emissions produced by signOut are captured below.
                states.clear()
                // Push the clock past the access token's lifetime minus the 60 s skew.
                now = NOW + EXPIRES_IN

                val result = manager.signOut()

                assertEquals(SignOutResult.SUCCESS, result)
                assertEquals(listOf(NEW_ACCESS to NEW_REFRESH), revoker.calls)
                assertEquals(
                    "Sign-out must go straight from the pre-existing state to Unauthenticated",
                    listOf(SessionState.Unauthenticated),
                    states,
                )
            } finally {
                collector.cancelAndJoin()
            }
        }

    @Test
    fun signOutWithUnconfirmedRevocationIsLocalOnlyAndStillClearsStorage() =
        runTest {
            val revoker = FakeSessionRevoker { _, _ -> throw IOException("offline") }
            val manager = newManager(revoker = revoker, clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))

            val result = manager.signOut()

            assertEquals(SignOutResult.LOCAL_ONLY, result)
            assertEquals(listOf(ACCESS_PROBE to REFRESH_PROBE), revoker.calls)
            assertNull(sessionTokenStore().refreshToken())
            assertNull(manager.currentAccessToken())
            assertEquals(SessionState.Unauthenticated, manager.state.value)
        }

    @Test
    fun signOutTreatsInvalidTokenOnRevocationAsAlreadyRevoked() =
        runTest {
            val revoker =
                FakeSessionRevoker { _, _ ->
                    throw ApiException(httpStatus = 401, code = ApiErrorCode.INVALID_TOKEN)
                }
            val manager = newManager(revoker = revoker, clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))

            val result = manager.signOut()

            assertEquals(SignOutResult.SUCCESS, result)
            assertEquals(listOf(ACCESS_PROBE to REFRESH_PROBE), revoker.calls)
            assertNull(sessionTokenStore().refreshToken())
            assertEquals(SessionState.Unauthenticated, manager.state.value)
        }

    @Test
    fun signOutWithoutPersistedRefreshTokenSkipsRevocation() =
        runTest {
            val revoker = FakeSessionRevoker()
            val manager = newManager(revoker = revoker, clock = { NOW })

            val result = manager.signOut()

            assertEquals(SignOutResult.SUCCESS, result)
            assertTrue("There is nothing to revoke: ${revoker.calls}", revoker.calls.isEmpty())
            assertNull(sessionTokenStore().refreshToken())
            assertEquals(SessionState.Unauthenticated, manager.state.value)
        }

    @Test
    fun signOutWithNoAccessTokenAndTransientRefreshFailureIsLocalOnly() =
        runTest {
            sessionTokenStore().saveRefreshToken(OLD_REFRESH)
            val revoker = FakeSessionRevoker()
            val manager = newManager(refresher = IoFailingRefresher, revoker = revoker, clock = { NOW })
            val events = collectEvents(this, manager)

            try {
                val result = manager.signOut()

                assertEquals(SignOutResult.LOCAL_ONLY, result)
                assertTrue("Refresh failed, so nothing was revoked: ${revoker.calls}", revoker.calls.isEmpty())
                assertNull(sessionTokenStore().refreshToken())
                assertNull(manager.currentAccessToken())
                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertTrue(
                    "Expected SignedOut(USER_INITIATED), got ${events.received}",
                    events.received.contains(
                        SessionEvent.SignedOut(SignOutReason.USER_INITIATED),
                    ),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun signOutOnTheAuthPathNeverTriggersTheAuthenticator() =
        runBlocking {
            val refreshHits = AtomicInteger()
            val logoutAuthorization = CopyOnWriteArrayList<String?>()
            dispatch { request ->
                when (request.url.encodedPath) {
                    LOGOUT_PATH -> {
                        logoutAuthorization += request.headers[AUTHORIZATION]
                        // Even a 401 here must not start a refresh: `/v1/auth/*` is excluded.
                        jsonResponse(401, errorJson("invalid_token"))
                    }
                    REFRESH_PATH -> {
                        refreshHits.incrementAndGet()
                        jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }

            var manager: SessionManager? = null
            val revoker =
                object : SessionRevoker {
                    override suspend fun revoke(
                        accessToken: String,
                        refreshToken: String,
                    ) {
                        AuthApiSessionRevoker(authApiOnSessionClient(checkNotNull(manager)))
                            .revoke(accessToken, refreshToken)
                    }
                }
            val created = newManager(revoker = revoker, clock = { NOW })
            manager = created
            created.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))

            val result = created.signOut()

            assertEquals(SignOutResult.SUCCESS, result)
            assertEquals(listOf("$BEARER$OLD_ACCESS"), logoutAuthorization)
            assertEquals("A 401 on /v1/auth/logout must not trigger a refresh", 0, refreshHits.get())
            assertNull(sessionTokenStore().refreshToken())
            assertEquals(SessionState.Unauthenticated, created.state.value)
        }

    @Test
    fun signOutWithUnreadableRefreshTokenIsLocalOnlyAndClearsWithoutRevoking() =
        runTest {
            val store = RefreshReadFailingTokenStore(sessionDir(), newScope())
            val revoker = FakeSessionRevoker()
            val manager = newManager(tokenStore = store, revoker = revoker, clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))

            val result = manager.signOut()

            assertEquals(SignOutResult.LOCAL_ONLY, result)
            assertTrue("There was nothing readable to revoke: ${revoker.calls}", revoker.calls.isEmpty())
            assertEquals(1, store.clearCalls.get())
            assertNull("The local token must be cleared", store.refreshToken())
            assertNull(manager.currentAccessToken())
            assertEquals(SessionState.Unauthenticated, manager.state.value)
        }

    @Test
    fun signOutWithFailingClearIsFailedKeepsResidualTokenAndEmitsNoEvent() =
        runTest {
            val store = ClearFailingTokenStore(sessionDir(), newScope())
            val manager = newManager(tokenStore = store, revoker = FakeSessionRevoker(), clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))
            val events = collectEvents(this, manager)

            try {
                val result = manager.signOut()

                assertEquals(SignOutResult.FAILED, result)
                assertEquals("Initial attempt plus one retry", 2, store.clearAttempts.get())
                assertNull(manager.currentAccessToken())
                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertFalse(
                    "A failed clear must not announce SignedOut: ${events.received}",
                    events.received.any { it is SessionEvent.SignedOut },
                )
                assertEquals(
                    "A failed delete leaves the documented residual token on disk",
                    REFRESH_PROBE,
                    store.refreshToken(),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun blankApiHostIsRejected() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SessionManager(
                    tokenStore = sessionTokenStore(),
                    tokenRefresher = IoFailingRefresher,
                    sessionRevoker = FakeSessionRevoker(),
                    apiHost = "   ",
                    timezoneProvider = timezoneProvider,
                )
            }

        assertTrue(
            "Expected an apiHost validation error, got ${error.message}",
            error.message.orEmpty().contains("apiHost"),
        )
    }

    @Test
    fun uppercaseApiHostIsNormalizedAndTheInterceptorStillAttaches() =
        runBlocking {
            val seen = CopyOnWriteArrayList<Triple<String, String, String?>>()
            dispatch { request ->
                when (request.url.encodedPath) {
                    RESOURCE_PATH -> {
                        seen += Triple(request.url.host, request.url.encodedPath, request.headers[AUTHORIZATION])
                        jsonResponse(200, "\"ok\"")
                    }
                    else -> jsonResponse(404, errorJson("not_found"))
                }
            }
            val manager = newManager(apiHost = API_HOST.uppercase())
            manager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))

            apiCall { probeApi(manager).resource() }

            assertEquals(OLD_ACCESS, manager.currentAccessToken())
            assertEquals(listOf(Triple(API_HOST, RESOURCE_PATH, "$BEARER$OLD_ACCESS")), seen)
        }

    @Test
    fun cancelledSignOutStillClearsLocalSessionAndPropagatesCancellation() =
        runBlocking {
            val revokeStarted = CompletableDeferred<Unit>()
            val revokeGate = CompletableDeferred<Unit>()
            val revoker =
                FakeSessionRevoker { _, _ ->
                    revokeStarted.complete(Unit)
                    revokeGate.await()
                }
            val manager = newManager(revoker = revoker, clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))
            val events = collectEvents(this, manager)

            try {
                val signOut =
                    async(Dispatchers.Default) {
                        manager.signOut()
                    }
                revokeStarted.await()
                signOut.cancel()
                signOut.join()
                val outcome = runCatching { signOut.await() }.exceptionOrNull()

                assertTrue(
                    "CancellationException must propagate, got $outcome",
                    outcome is CancellationException,
                )
                assertNull("The local clear must still run", sessionTokenStore().refreshToken())
                assertNull(manager.currentAccessToken())
                assertEquals(SessionState.Unauthenticated, manager.state.value)
                assertTrue(
                    "The durable local sign-out must still be announced: ${events.received}",
                    events.received.contains(SessionEvent.SignedOut(SignOutReason.USER_INITIATED)),
                )
            } finally {
                events.close()
            }
        }

    @Test
    fun cancelledSignOutDuringRefreshTokenReadStillClearsLocalSessionAndPropagatesCancellation() =
        runBlocking {
            val store = GatedReadTokenStore(sessionDir(), newScope())
            val revoker = FakeSessionRevoker()
            val manager = newManager(tokenStore = store, revoker = revoker, clock = { NOW })
            manager.signIn(Tokens(ACCESS_PROBE, REFRESH_PROBE, EXPIRES_IN))

            val signOut =
                async(Dispatchers.Default) {
                    manager.signOut()
                }
            store.readStarted.await()
            signOut.cancel()
            signOut.join()
            val outcome = runCatching { signOut.await() }.exceptionOrNull()

            assertTrue(
                "CancellationException must propagate, got $outcome",
                outcome is CancellationException,
            )
            assertNull("The local clear must still run", store.refreshToken())
            assertNull(manager.currentAccessToken())
            assertEquals(SessionState.Unauthenticated, manager.state.value)
            assertTrue(
                "A cancelled read cannot attempt revocation: ${revoker.calls}",
                revoker.calls.isEmpty(),
            )
        }

    private fun newManager(
        refresher: TokenRefresher = refreshRefresher(),
        revoker: SessionRevoker = FakeSessionRevoker(),
        clock: () -> Long = { System.currentTimeMillis() / 1000 },
        tokenStore: TokenStore = sessionTokenStore(),
        apiHost: String = API_HOST,
    ): SessionManager =
        SessionManager(
            tokenStore = tokenStore,
            tokenRefresher = refresher,
            sessionRevoker = revoker,
            apiHost = apiHost,
            timezoneProvider = timezoneProvider,
            clock = clock,
        )

    private fun refreshRefresher(): TokenRefresher =
        AuthApiTokenRefresher(
            NetworkModule.createAuthApi(
                baseUrl = apiBaseUrl(),
                client = OkHttpClient.Builder().dns(apiHostDns()).build(),
            ),
        )

    private fun authApiOnSessionClient(manager: SessionManager): AuthApi =
        NetworkModule.createAuthApi(
            baseUrl = apiBaseUrl(),
            client = sessionClient(manager),
        )

    private fun probeApi(
        manager: SessionManager,
        extraInterceptor: Interceptor? = null,
        baseUrl: String = apiBaseUrl(),
    ): ProbeApi {
        val builder =
            OkHttpClient
                .Builder()
                .dns(apiHostDns())
                .addInterceptor(manager.authInterceptor)
        if (extraInterceptor != null) {
            builder.addInterceptor(extraInterceptor)
        }
        val client = builder.authenticator(manager.authenticator).build()
        return NetworkModule.createRetrofit(baseUrl, client).create(ProbeApi::class.java)
    }

    private fun sessionClient(manager: SessionManager): OkHttpClient =
        OkHttpClient
            .Builder()
            .dns(apiHostDns())
            .addInterceptor(manager.authInterceptor)
            .authenticator(manager.authenticator)
            .build()

    /**
     * MockWebServer binds to loopback, but session tests must exercise the real API host. Requests
     * to [API_HOST] are resolved to the running server; any other host (e.g. the server's own
     * `localhost` URL) is genuinely foreign.
     */
    private fun apiBaseUrl(): String =
        server
            .url("/")
            .newBuilder()
            .host(API_HOST)
            .build()
            .toString()

    private fun apiHostDns(): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == API_HOST) {
                    listOf(server.socketAddress.address)
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
        }

    private fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
            }
    }

    private fun sessionTokenStore(): TokenStore = tokenStore ?: TokenStore(sessionDir(), newScope()).also { tokenStore = it }

    private fun newScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return scope
    }

    private fun sessionDir(): File = File(temporaryFolder.root, "no-backup")

    private fun sessionFile(): File = File(sessionDir(), "datastore/session.preferences_pb")

    private suspend fun writeRawRefreshToken(value: String) {
        val scope = newScope()
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { sessionFile() },
            )
        try {
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("refresh_token")] = value
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun collectEvents(
        scope: CoroutineScope,
        manager: SessionManager,
    ): EventProbe = EventProbe(scope, manager)

    private class EventProbe(
        scope: CoroutineScope,
        manager: SessionManager,
    ) {
        val received = CopyOnWriteArrayList<SessionEvent>()
        private val job =
            scope.launch(Dispatchers.Unconfined) {
                manager.events.collect { event -> received += event }
            }

        suspend fun close() {
            job.cancelAndJoin()
        }
    }

    /** Fails the next [refreshToken] read, then behaves normally so the test can inspect the store. */
    private class RefreshReadFailingTokenStore(
        sessionDir: File,
        scope: CoroutineScope,
    ) : TokenStore(sessionDir, scope) {
        private var failNextRead = true
        val clearCalls = AtomicInteger()

        override suspend fun refreshToken(): String? {
            if (failNextRead) {
                failNextRead = false
                throw IOException("read failed")
            }
            return super.refreshToken()
        }

        override suspend fun clear() {
            clearCalls.incrementAndGet()
            super.clear()
        }
    }

    /** Every delete fails with [IOException], exercising the failed-clear path. */
    private class ClearFailingTokenStore(
        sessionDir: File,
        scope: CoroutineScope,
    ) : TokenStore(sessionDir, scope) {
        val clearAttempts = AtomicInteger()

        override suspend fun clear() {
            clearAttempts.incrementAndGet()
            throw IOException("clear failed")
        }
    }

    /**
     * Suspends the first [refreshToken] read until the test cancels the caller. Later reads bypass
     * the gate so assertions can inspect the persisted value after sign-out.
     */
    private class GatedReadTokenStore(
        sessionDir: File,
        scope: CoroutineScope,
    ) : TokenStore(sessionDir, scope) {
        val readStarted = CompletableDeferred<Unit>()
        private val readGate = CompletableDeferred<Unit>()
        private val gateNextRead = AtomicBoolean(true)

        override suspend fun refreshToken(): String? {
            if (gateNextRead.compareAndSet(true, false)) {
                readStarted.complete(Unit)
                readGate.await()
            }
            return super.refreshToken()
        }
    }

    private object IoFailingRefresher : TokenRefresher {
        override suspend fun refresh(
            refreshToken: String,
            timezone: String,
        ): Tokens = throw IOException("offline")
    }

    private class FakeSessionRevoker(
        private val onRevoke: suspend (String, String) -> Unit = { _, _ -> },
    ) : SessionRevoker {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun revoke(
            accessToken: String,
            refreshToken: String,
        ) {
            calls += accessToken to refreshToken
            onRevoke(accessToken, refreshToken)
        }
    }

    private class BarrierInterceptor(
        parties: Int,
    ) : Interceptor {
        private val latch = CountDownLatch(parties)

        override fun intercept(chain: Interceptor.Chain): Response {
            latch.countDown()
            latch.await(5, TimeUnit.SECONDS)
            return chain.proceed(chain.request())
        }
    }

    private interface ProbeApi {
        @GET("v1/resource")
        suspend fun resource(): String
    }

    private fun jsonResponse(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()

    private fun refreshTokenFrom(request: RecordedRequest): String =
        NetworkJson
            .parseToJsonElement(request.body?.utf8().orEmpty())
            .jsonObject
            .getValue("refresh_token")
            .jsonPrimitive
            .content

    private fun assertNoTokenInException(error: Throwable?) {
        val messages =
            generateSequence(error) { it.cause }
                .mapNotNull { it.message }
                .toList()
        listOf(OLD_ACCESS, OLD_REFRESH, NEW_ACCESS, NEW_REFRESH).forEach { token ->
            assertFalse("Exception messages must not contain a token: $messages", messages.any { it.contains(token) })
        }
    }

    private companion object {
        const val OLD_ACCESS = "probe-old-access-4f1c"
        const val OLD_REFRESH = "probe-old-refresh-9a2d"
        const val NEW_ACCESS = "probe-new-access-7b3e"
        const val NEW_REFRESH = "probe-new-refresh-1c8f"
        const val ACCESS_PROBE = "probe-session-access-6d2a"
        const val REFRESH_PROBE = "probe-session-refresh-3e7b"
        const val EXPIRES_IN = 900L
        const val NOW = 1_000_000L
        const val OVERSIZED_BODY_BYTES = 128 * 1024
        val CORRUPT_STORE_BYTES = "this-is-not-a-preferences-file".toByteArray(Charsets.UTF_8)

        const val API_HOST = "api.example.test"
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer "
        const val RESOURCE_PATH = "/v1/resource"
        const val REFRESH_PATH = "/v1/auth/refresh"
        const val LOGIN_PATH = "/v1/auth/login"
        const val LOGOUT_PATH = "/v1/auth/logout"

        fun tokensJson(
            accessToken: String,
            refreshToken: String,
        ): String =
            """{"access_token":"$accessToken","refresh_token":"$refreshToken",""" +
                """"expires_in":$EXPIRES_IN}"""

        fun authResponseJson(): String =
            """{"tokens":${tokensJson(NEW_ACCESS, NEW_REFRESH)},""" +
                """"user":{"id":1,"email":"user@example.com","nickname":"probe",""" +
                """"email_verified":true,"daily_goal":10,"hide_from_ranking":false,""" +
                """"onboarding_completed":true,"stats":{"total_xp":0,"level":1,"level_xp":0,""" +
                """"level_xp_needed":100,"current_streak":0,"longest_streak":0,""" +
                """"streak_freezes":0,"today":{"earned":0,"goal":10,"met":false}}}}"""

        fun errorJson(code: String): String = """{"error":{"code":"$code"}}"""
    }
}
