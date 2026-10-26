package com.buzzness.buzzlearn.data

import android.content.Context
import android.util.Log
import com.buzzness.buzzlearn.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual dependency container (ADR-001).
 *
 * Create **exactly one** instance per process and hold it in the `Application`; [SessionManager]
 * and [TokenStore] both assume a single instance (DataStore forbids two stores over the same file,
 * and two managers would keep divergent in-memory tokens).
 *
 * The production constructor stores the session directory under
 * `context.noBackupFilesDir/session`, which keeps the refresh token out of cloud and
 * device-to-device backups. The primary constructor takes the resolved [sessionDir] directly so
 * JVM tests can inject a temporary directory without Robolectric or a mockable [Context].
 *
 * [start] boots the session restore. It is idempotent: only the first call launches the restore,
 * because repeated calls would race the single-flight refresh for no benefit.
 */
class AppContainer internal constructor(
    baseUrl: String,
    internal val sessionDir: File,
    private val scope: CoroutineScope,
) {
    /** Production entry point: `context.noBackupFilesDir/session` by default. */
    constructor(
        context: Context,
        baseUrl: String = BuildConfig.BASE_URL,
        sessionDir: File = File(context.noBackupFilesDir, "session"),
        scope: CoroutineScope = defaultScope(),
    ) : this(baseUrl, sessionDir, scope)

    private val started = AtomicBoolean(false)
    private val components =
        SessionComponents.create(
            baseUrl = baseUrl,
            sessionDir = sessionDir,
            scope = scope,
        )

    val authApi get() = components.authApi
    val sessionManager get() = components.sessionManager
    val sessionClient get() = components.sessionClient

    /**
     * Starts the session restore and returns whether this call performed it. Only the first call
     * returns true; callers may ignore the result.
     */
    fun start(): Boolean =
        started.compareAndSet(false, true).also { didStart ->
            if (didStart) {
                scope.launch { sessionManager.restoreSession() }
            }
        }

    fun close() {
        scope.cancel()
    }

    private companion object {
        fun defaultScope(): CoroutineScope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.IO +
                    CoroutineExceptionHandler { _, throwable ->
                        // Class name only: messages can carry tokens or PII (RNF-14).
                        Log.e("BuzzLearnApp", "App scope failure: ${throwable::class.java.simpleName}")
                    },
            )
    }
}
