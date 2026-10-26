package com.buzzness.buzzlearn.data.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [TokenStore]. No Robolectric: DataStore's preferences factory works on the plain
 * JVM, and [TokenStore] accepts the directory and the scope so both can be controlled here.
 */
class TokenStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val openScopes = mutableListOf<CoroutineScope>()

    private val sessionDir: File
        get() = File(temporaryFolder.root, "no-backup")

    @After
    fun cancelOpenScopes() {
        openScopes.forEach { it.cancel() }
    }

    @Test
    fun savesUnderInjectedDirectoryOnly() =
        runTest {
            val filesDirLike = temporaryFolder.newFolder("files")
            val handle = openStore()

            try {
                handle.store.saveRefreshToken(REFRESH_TOKEN)

                val expected = sessionFile()
                assertTrue("Expected preferences file at ${expected.path}", expected.isFile)
                assertEquals("preferences_pb", expected.extension)
                assertEquals("datastore", expected.parentFile?.name)
                assertTrue(
                    "File must live under the injected directory: ${expected.canonicalPath}",
                    expected.canonicalPath.startsWith(sessionDir.canonicalPath + File.separator),
                )

                assertEquals(emptyList<File>(), filesDirLike.listFiles()?.toList().orEmpty())
                val preferencesFiles =
                    temporaryFolder.root
                        .walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".preferences_pb") }
                        .map { it.canonicalFile }
                        .toList()
                assertEquals(listOf(expected.canonicalFile), preferencesFiles)
            } finally {
                handle.close()
            }
        }

    @Test
    fun saveReadClearRoundTrip() =
        runTest {
            val handle = openStore()

            try {
                assertNull(handle.store.refreshToken())

                handle.store.saveRefreshToken(REFRESH_TOKEN)
                assertEquals(REFRESH_TOKEN, handle.store.refreshToken())

                handle.store.clear()
                assertNull(handle.store.refreshToken())
            } finally {
                handle.close()
            }
        }

    @Test
    fun refreshTokenSurvivesNewStoreInstance() =
        runTest {
            val first = openStore()
            try {
                first.store.saveRefreshToken(REFRESH_TOKEN)
            } finally {
                first.close()
            }

            val second = openStore()
            try {
                assertEquals(REFRESH_TOKEN, second.store.refreshToken())
            } finally {
                second.close()
            }
        }

    @Test
    fun preferencesFileStoresOnlyTheRefreshTokenKey() =
        runTest {
            val handle = openStore()
            try {
                handle.store.saveRefreshToken(REFRESH_TOKEN)
            } finally {
                handle.close()
            }

            val file = sessionFile()
            val rawText = file.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue("Expected the refresh_token key in ${file.path}", rawText.contains("refresh_token"))
            assertTrue("Expected the token value to be persisted", rawText.contains(REFRESH_TOKEN))
            assertFalse("Access token key must never be persisted", rawText.contains("access_token"))
            assertFalse("Access token value must never be persisted", rawText.contains(ACCESS_TOKEN_PROBE))
            assertFalse("Email must never be persisted", rawText.contains(EMAIL_PROBE))

            val readerScope = newScope()
            val reader = PreferenceDataStoreFactory.create(scope = readerScope, produceFile = { file })
            try {
                val preferences = reader.data.first()
                val storedKeys =
                    preferences
                        .asMap()
                        .keys
                        .map { it.name }
                        .toSet()
                assertEquals(setOf("refresh_token"), storedKeys)
                assertEquals(REFRESH_TOKEN, preferences.asMap().values.single())
            } finally {
                readerScope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test
    fun corruptedPreferencesFileIsResetAndStoreRemainsUsable() =
        runTest {
            val first = openStore()
            try {
                first.store.saveRefreshToken(REFRESH_TOKEN)
            } finally {
                first.close()
            }

            val file = sessionFile()
            file.writeBytes("this-is-not-a-preferences-file".toByteArray(Charsets.UTF_8))

            val recovered = openStore()
            try {
                assertNull(recovered.store.refreshToken())

                recovered.store.saveRefreshToken(REPLACEMENT_TOKEN)
                assertEquals(REPLACEMENT_TOKEN, recovered.store.refreshToken())
            } finally {
                recovered.close()
            }

            val afterRestart = openStore()
            try {
                assertEquals(REPLACEMENT_TOKEN, afterRestart.store.refreshToken())
            } finally {
                afterRestart.close()
            }
        }

    @Test
    fun rejectsBlankRefreshTokenWithoutPersistingIt() =
        runTest {
            val handle = openStore()

            try {
                listOf("", "   ", "\n\t").forEach { blank ->
                    val error = runCatching { handle.store.saveRefreshToken(blank) }.exceptionOrNull()
                    assertTrue("Expected IllegalArgumentException for \"$blank\" but got $error", error is IllegalArgumentException)
                    assertNull("Blank token \"$blank\" must not be persisted", handle.store.refreshToken())
                }
            } finally {
                handle.close()
            }
        }

    @Test
    fun specialCharactersSurviveTheRoundTripUnchanged() =
        runTest {
            val handle = openStore()
            val hostile = "probe-<script>alert(1)</script>-\"quoted\"-\\backslash\n-newline\t-%s-ñ"

            try {
                handle.store.saveRefreshToken(hostile)
                // Stored verbatim: nothing interprets or rewrites the token on the way to disk.
                assertEquals(hostile, handle.store.refreshToken())
            } finally {
                handle.close()
            }

            val reopened = openStore()
            try {
                assertEquals(hostile, reopened.store.refreshToken())
            } finally {
                reopened.close()
            }
        }

    @Test
    fun rejectsOversizedRefreshTokenWithoutPersistingOrEchoingIt() =
        runTest {
            val handle = openStore()
            val oversized = OVERSIZED_PROBE + "x".repeat(MAX_REFRESH_TOKEN_BYTES)

            try {
                val error = runCatching { handle.store.saveRefreshToken(oversized) }.exceptionOrNull()
                assertTrue("Expected IllegalArgumentException but got $error", error is IllegalArgumentException)

                val message = error?.message.orEmpty()
                assertFalse("Validation message must not echo the token", message.contains(oversized))
                assertFalse("Validation message must not echo a token fragment", message.contains(OVERSIZED_PROBE))

                assertNull(handle.store.refreshToken())
                // Nothing may reach disk for a rejected save, not even the token prefix.
                val file = sessionFile()
                if (file.isFile) {
                    val raw = file.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse("Rejected token must not reach disk", raw.contains(OVERSIZED_PROBE))
                }
            } finally {
                handle.close()
            }
        }

    @Test
    fun acceptsTokenAtTheSizeLimitAndKeepsThePreviousValueOnOverflow() =
        runTest {
            val handle = openStore()
            val atLimit = TOKEN_AT_LIMIT_PROBE + "x".repeat(MAX_REFRESH_TOKEN_BYTES - TOKEN_AT_LIMIT_PROBE.length)

            try {
                // ASCII: one byte per character, so the boundary is the same as before.
                assertEquals(MAX_REFRESH_TOKEN_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)
                handle.store.saveRefreshToken(atLimit)
                assertEquals(atLimit, handle.store.refreshToken())

                val error = runCatching { handle.store.saveRefreshToken(atLimit + "x") }.exceptionOrNull()
                assertTrue("Expected IllegalArgumentException but got $error", error is IllegalArgumentException)
                // A rejected save must not discard or overwrite the token already persisted.
                assertEquals(atLimit, handle.store.refreshToken())
            } finally {
                handle.close()
            }
        }

    @Test
    fun acceptsMultibyteTokenAtTheByteLimit() =
        runTest {
            val handle = openStore()
            // 'ñ' is two UTF-8 bytes, so 4096 characters are exactly 8192 bytes.
            val atLimit = "ñ".repeat(MAX_REFRESH_TOKEN_BYTES / 2)

            try {
                assertEquals(MAX_REFRESH_TOKEN_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)
                handle.store.saveRefreshToken(atLimit)
                assertEquals(atLimit, handle.store.refreshToken())
            } finally {
                handle.close()
            }
        }

    @Test
    fun rejectsMultibyteTokenOverTheByteLimitEvenWhenUnderTheCharLimit() =
        runTest {
            val handle = openStore()
            // 4097 characters but 8194 UTF-8 bytes: a character-based check would accept it.
            val overLimit = "ñ".repeat(MAX_REFRESH_TOKEN_BYTES / 2 + 1)

            try {
                assertTrue(overLimit.length <= MAX_REFRESH_TOKEN_BYTES)
                assertEquals(MAX_REFRESH_TOKEN_BYTES + 2, overLimit.toByteArray(Charsets.UTF_8).size)

                val error = runCatching { handle.store.saveRefreshToken(overLimit) }.exceptionOrNull()
                assertTrue("Expected IllegalArgumentException but got $error", error is IllegalArgumentException)
                assertNull(handle.store.refreshToken())
            } finally {
                handle.close()
            }
        }

    @Test
    fun blankPersistedTokenIsTreatedAsNoSession() =
        runTest {
            // `saveRefreshToken` rejects blank values, so a blank can only appear through external
            // corruption or a future writer. The read path must still treat it as "no session".
            writeRawRefreshToken("   ")

            val handle = openStore()
            try {
                assertNull(handle.store.refreshToken())
            } finally {
                handle.close()
            }
        }

    private suspend fun writeRawRefreshToken(value: String) {
        val scope = newScope()
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { sessionFile() },
            )
        try {
            dataStore.edit { preferences -> preferences[stringPreferencesKey("refresh_token")] = value }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun openStore(): OpenStore {
        val scope = newScope()
        return OpenStore(TokenStore(sessionDir, scope), scope)
    }

    private fun newScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        openScopes += scope
        return scope
    }

    private fun sessionFile(): File = File(sessionDir, "datastore/session.preferences_pb")

    private class OpenStore(
        val store: TokenStore,
        private val scope: CoroutineScope,
    ) {
        /**
         * Cancels the store's scope and waits for DataStore to release the file. DataStore rejects
         * a second active instance over the same path, so tests that reopen the file must close.
         */
        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private companion object {
        const val REFRESH_TOKEN = "probe-refresh-token-7a2d"
        const val REPLACEMENT_TOKEN = "probe-refresh-token-recovered-1c9e"
        const val ACCESS_TOKEN_PROBE = "probe-access-token-51d0"
        const val EMAIL_PROBE = "probe-user@example.test"
        const val OVERSIZED_PROBE = "probe-oversized-token-2f81-"
        const val TOKEN_AT_LIMIT_PROBE = "probe-limit-token-6d4a-"

        // Mirrors TokenStore's documented 8 KiB ceiling; pinned here so an off-by-one in the
        // production limit shows up as a test failure instead of a silent behaviour change.
        const val MAX_REFRESH_TOKEN_BYTES = 8 * 1024
    }
}
