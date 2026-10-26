package com.buzzness.buzzlearn.data.session

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Persists the session refresh token in the app's private, non-backed-up storage.
 *
 * Only the refresh token is stored. The access token stays in memory (owned by the session layer
 * in S1c) and this class deliberately exposes no API to persist it (ADR-001 session policy).
 *
 * [sessionDir] is injected so this class never references `Context`; in production the app passes
 * `context.noBackupFilesDir`, which keeps the token out of cloud and device-to-device backups.
 * The DataStore file lives under `datastore/session.preferences_pb` inside that directory, and the
 * `.preferences_pb` suffix is required by DataStore's preferences serializer.
 *
 * **Single instance per process:** [com.buzzness.buzzlearn.data.AppContainer] must create and hold
 * exactly one [TokenStore]. DataStore forbids two active instances over the same file, and two
 * instances would also keep divergent in-memory caches. Never instantiate this per screen or call.
 *
 * Corruption (for example a truncated write) is replaced by empty preferences, so the app starts
 * unauthenticated and asks the user to log in again instead of crashing.
 *
 * The class and its public suspending methods are `open` only so tests can inject I/O failures
 * (unreadable/unwritable storage). Production has a single concrete implementation; there is no
 * extension point by design.
 */
open class TokenStore(
    sessionDir: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dataStore =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { File(sessionDir, SESSION_FILE_PATH) },
        )

    /** Returns the persisted refresh token, or null when there is no session or it was cleared. */
    open suspend fun refreshToken(): String? = dataStore.data.first()[REFRESH_TOKEN_KEY]?.takeIf { it.isNotBlank() }

    /**
     * Stores [token] as the only persisted session value.
     *
     * The token comes from the network, so it is validated before it reaches storage. The limit is
     * measured in UTF-8 bytes (not characters) so a multi-byte token cannot exceed the 8 KiB
     * budget documented in ADR-001. Error messages never include the value (RNF-14).
     */
    open suspend fun saveRefreshToken(token: String) {
        require(token.isNotBlank()) { "Refresh token must not be blank." }
        require(token.toByteArray(Charsets.UTF_8).size <= MAX_REFRESH_TOKEN_BYTES) {
            "Refresh token is too long."
        }
        dataStore.edit { preferences -> preferences[REFRESH_TOKEN_KEY] = token }
    }

    /** Removes the persisted session. The access token is not touched because it is never stored. */
    open suspend fun clear() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private companion object {
        const val SESSION_FILE_PATH = "datastore/session.preferences_pb"
        const val MAX_REFRESH_TOKEN_BYTES = 8 * 1024

        val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    }
}
