package com.buzzness.buzzlearn.data

import com.buzzness.buzzlearn.data.remote.dto.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [AppContainer].
 *
 * `ContextWrapper(null)` is a stubbed constructor in the local `android.jar`, so a fake
 * `Context` does not work without Robolectric. The container therefore has an internal
 * constructor that takes the resolved session directory directly; these tests use that seam.
 */
class AppContainerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val openScopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelOpenScopes() {
        openScopes.forEach { it.cancel() }
    }

    @Test
    fun injectsTheSessionDirectoryAndExposesOneSessionManager() {
        val root = temporaryFolder.newFolder("no-backup")
        val container = newContainer(root)

        try {
            assertEquals(File(root, "session"), container.sessionDir)
            assertSame(
                "Every caller must see the same SessionManager instance",
                container.sessionManager,
                container.sessionManager,
            )
            assertNotNull(container.authApi)
            assertNotNull(container.sessionClient)
        } finally {
            container.close()
        }
    }

    @Test
    fun startIsIdempotent() {
        val root = temporaryFolder.newFolder("no-backup")
        val container = newContainer(root)

        try {
            assertTrue("The first start must launch the restore", container.start())
            assertFalse("Subsequent starts must be no-ops", container.start())
        } finally {
            container.close()
        }
    }

    @Test
    fun sessionManagerPersistsUnderTheInjectedSessionDirectory() =
        runBlocking {
            val root = temporaryFolder.newFolder("no-backup")
            val container = newContainer(root)

            try {
                container.sessionManager.signIn(
                    Tokens(
                        accessToken = "probe-access-token",
                        refreshToken = "probe-refresh-token",
                        expiresIn = 3600L,
                    ),
                )

                val sessionFile = File(container.sessionDir, "datastore/session.preferences_pb")
                assertTrue("Expected the session file at ${sessionFile.path}", sessionFile.isFile)
            } finally {
                container.close()
            }
        }

    private fun newContainer(root: File): AppContainer {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        openScopes += scope
        return AppContainer(
            baseUrl = BASE_URL,
            sessionDir = File(root, "session"),
            scope = scope,
        )
    }

    private companion object {
        const val BASE_URL = "https://api.example.test/"
    }
}
