package com.buzzness.buzzlearn.data.session

/**
 * High-level session state observed by the UI layer.
 *
 * [Unknown] is the initial state before [SessionManager.restoreSession] runs or while a restore
 * attempt failed transiently (network error). It never means "the user has a session": consumers
 * still need to wait for a definite [Authenticated] / [Unauthenticated] transition.
 *
 * [Authenticated] only means an access token is available in memory. The token is never persisted
 * and is lost on process death, so a cold start always goes through [Authenticated] again via a
 * refresh (ADR-001 session policy).
 */
sealed interface SessionState {
    data object Unknown : SessionState

    data object Unauthenticated : SessionState

    data object Authenticated : SessionState
}
