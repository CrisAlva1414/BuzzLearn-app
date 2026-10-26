package com.buzzness.buzzlearn.data

import com.buzzness.buzzlearn.data.remote.NetworkJson
import com.buzzness.buzzlearn.data.remote.NetworkLogging
import com.buzzness.buzzlearn.data.remote.NetworkModule
import com.buzzness.buzzlearn.data.remote.dto.Tokens
import com.buzzness.buzzlearn.data.session.SessionState
import com.buzzness.buzzlearn.data.session.SignOutResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import retrofit2.http.GET
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration-style JVM tests for the **S1d wiring** in [SessionComponents].
 *
 * Each test builds the real SessionManager graph (dedicated refresh/revoker clients, real
 * `Authenticator` and `Interceptor`) and drives it through MockWebServer. The point is not the
 * single-flight logic itself — [com.buzzness.buzzlearn.data.session.SessionManagerTest] covers
 * that — but that the clients are wired so concurrent 401s cannot starve the refresh/revoker.
 *
 * The concurrency windows are opened with latches only: no `Thread.sleep`, so the tests are
 * deterministic on a fast or slow machine.
 */
class SessionConcurrencyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val openScopes = mutableListOf<CoroutineScope>()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        openScopes.forEach { scope -> scope.cancel() }
        server.close()
    }

    @Test
    fun concurrent401sDoNotDeadlockAndShareOneRefresh() =
        runBlocking {
            val refreshCalls = AtomicInteger()
            val refreshAuthorizations = CopyOnWriteArrayList<String?>()
            val refreshTokens = CopyOnWriteArrayList<String>()
            val resourceAuthorizations = CopyOnWriteArrayList<String?>()

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            RESOURCE_PATH -> {
                                val authorization = request.headers[AUTHORIZATION]
                                resourceAuthorizations += authorization
                                when (authorization) {
                                    "$BEARER$OLD_ACCESS" -> jsonResponse(401, errorJson(INVALID_TOKEN))
                                    "$BEARER$NEW_ACCESS" -> okResponse()
                                    else -> jsonResponse(401, errorJson(INVALID_TOKEN))
                                }
                            }
                            REFRESH_PATH -> {
                                refreshCalls.incrementAndGet()
                                refreshAuthorizations += request.headers[AUTHORIZATION]
                                refreshTokens += refreshTokenFrom(request.body?.utf8().orEmpty())
                                jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                            }
                            else -> jsonResponse(404, errorJson("not_found"))
                        }
                }

            val components = newComponents()
            components.sessionManager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val api = resourceApi(components, BarrierInterceptor(CONCURRENT_CALLS))

            val responses =
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    coroutineScope {
                        (1..CONCURRENT_CALLS)
                            .map { async(Dispatchers.IO) { api.resource() } }
                            .awaitAll()
                    }
                }

            responses.forEach { response ->
                assertEquals("Every call must eventually succeed", 200, response.code())
                response.body()?.close()
            }
            assertEquals("Concurrent 401s must share exactly one refresh", 1, refreshCalls.get())
            assertTrue(
                "The refresh request must not carry an Authorization header: $refreshAuthorizations",
                refreshAuthorizations.all { it == null },
            )
            assertEquals(listOf(OLD_REFRESH), refreshTokens)
            assertEquals(
                "Every initial call must carry the old token",
                CONCURRENT_CALLS,
                resourceAuthorizations.count { it == "$BEARER$OLD_ACCESS" },
            )
            assertEquals(
                "Every retry must carry the rotated token",
                CONCURRENT_CALLS,
                resourceAuthorizations.count { it == "$BEARER$NEW_ACCESS" },
            )
            assertEquals(NEW_REFRESH, components.tokenStore.refreshToken())
            assertEquals(SessionState.Authenticated, components.sessionManager.state.value)
        }

    @Test
    fun signOutWhileRequestsAreInFlightCompletesWithoutResurrectingTokens() =
        runBlocking {
            val arrived = CountDownLatch(CONCURRENT_CALLS)
            val release = CountDownLatch(1)
            val refreshCalls = AtomicInteger()
            val logoutAuthorizations = CopyOnWriteArrayList<String?>()
            val logoutBodies = CopyOnWriteArrayList<String>()

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            RESOURCE_PATH -> {
                                when (request.headers[AUTHORIZATION]) {
                                    "$BEARER$OLD_ACCESS" -> {
                                        // Hold every initial request until the test has all of
                                        // them in flight and signOut has run.
                                        arrived.countDown()
                                        release.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                        jsonResponse(401, errorJson(INVALID_TOKEN))
                                    }
                                    "$BEARER$NEW_ACCESS" -> okResponse()
                                    else -> jsonResponse(401, errorJson(INVALID_TOKEN))
                                }
                            }
                            REFRESH_PATH -> {
                                refreshCalls.incrementAndGet()
                                jsonResponse(200, tokensJson(NEW_ACCESS, NEW_REFRESH))
                            }
                            LOGOUT_PATH -> {
                                logoutAuthorizations += request.headers[AUTHORIZATION]
                                logoutBodies += request.body?.utf8().orEmpty()
                                MockResponse.Builder().code(204).build()
                            }
                            else -> jsonResponse(404, errorJson("not_found"))
                        }
                }

            val components = newComponents()
            components.sessionManager.signIn(Tokens(OLD_ACCESS, OLD_REFRESH, EXPIRES_IN))
            val api = resourceApi(components)

            val resourceCalls =
                (1..CONCURRENT_CALLS).map { async(Dispatchers.IO) { api.resource() } }

            try {
                assertTrue(
                    "All $CONCURRENT_CALLS resource calls must reach the server before signOut",
                    arrived.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )

                val signOut = async(Dispatchers.IO) { components.sessionManager.signOut() }
                assertEquals(
                    "signOut must not wait for the in-flight session requests",
                    SignOutResult.SUCCESS,
                    withTimeout(TEST_TIMEOUT_MILLIS) { signOut.await() },
                )
            } finally {
                // Let the held 401s complete even if an assertion above failed, so no server
                // thread is left blocked and the test can report the real failure.
                release.countDown()
            }

            val responses = withTimeout(TEST_TIMEOUT_MILLIS) { resourceCalls.awaitAll() }

            assertEquals(CONCURRENT_CALLS, responses.size)
            responses.forEach { response ->
                assertEquals("The old token must fail after signOut", 401, response.code())
                response.body()?.close()
            }
            assertEquals("signOut must issue exactly one logout", 1, logoutAuthorizations.size)
            assertEquals("$BEARER$OLD_ACCESS", logoutAuthorizations.single())
            assertEquals(OLD_REFRESH, refreshTokenFrom(logoutBodies.single()))
            assertEquals("No refresh may run after signOut", 0, refreshCalls.get())
            assertEquals(SessionState.Unauthenticated, components.sessionManager.state.value)
            assertNull("The refresh token must be deleted", components.tokenStore.refreshToken())
            assertNull("The in-memory access token must be cleared", components.sessionManager.currentAccessToken())
        }

    private fun newComponents(): SessionComponents {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        openScopes += scope
        return SessionComponents.create(
            server.url("/").toString(),
            File(temporaryFolder.root, "session"),
            scope,
            NoOpNetworkLogging,
        )
    }

    private fun resourceApi(
        components: SessionComponents,
        extraInterceptor: Interceptor? = null,
    ): ResourceApi {
        val client =
            if (extraInterceptor == null) {
                components.sessionClient
            } else {
                components.sessionClient
                    .newBuilder()
                    .addInterceptor(extraInterceptor)
                    .build()
            }
        return NetworkModule
            .createRetrofit(components.baseUrl.toString(), client)
            .create(ResourceApi::class.java)
    }

    private class BarrierInterceptor(
        parties: Int,
    ) : Interceptor {
        private val latch = CountDownLatch(parties)

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            latch.countDown()
            check(latch.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "The initial requests did not overlap in time."
            }
            return chain.proceed(chain.request())
        }
    }

    private interface ResourceApi {
        @GET("v1/resource")
        suspend fun resource(): Response<ResponseBody>
    }

    private object NoOpNetworkLogging : NetworkLogging {
        override fun install(builder: OkHttpClient.Builder) = Unit
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

    private fun okResponse(): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .setHeader("Content-Type", "text/plain; charset=utf-8")
            .body("ok")
            .build()

    private fun tokensJson(
        accessToken: String,
        refreshToken: String,
    ): String = """{"access_token":"$accessToken","refresh_token":"$refreshToken","expires_in":$EXPIRES_IN}"""

    private fun errorJson(code: String): String = """{"error":{"code":"$code"}}"""

    private fun refreshTokenFrom(body: String): String =
        NetworkJson
            .parseToJsonElement(body)
            .jsonObject
            .getValue("refresh_token")
            .jsonPrimitive
            .content

    private companion object {
        const val CONCURRENT_CALLS = 5
        const val TEST_TIMEOUT_MILLIS = 15_000L
        const val BARRIER_TIMEOUT_SECONDS = 5L

        const val OLD_ACCESS = "old-access"
        const val OLD_REFRESH = "old-refresh"
        const val NEW_ACCESS = "new-access"
        const val NEW_REFRESH = "new-refresh"
        const val EXPIRES_IN = 3600L

        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer "
        const val INVALID_TOKEN = "invalid_token"
        const val RESOURCE_PATH = "/v1/resource"
        const val REFRESH_PATH = "/v1/auth/refresh"
        const val LOGOUT_PATH = "/v1/auth/logout"
    }
}
