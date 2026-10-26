package com.buzzness.buzzlearn.data.session

/**
 * One-shot session signals for consumers that must react (navigation, cache wipe) instead of
 * merely render state. Emitted through a buffered [kotlinx.coroutines.flow.SharedFlow].
 */
sealed interface SessionEvent {
    /**
     * The session ended and the persisted refresh token was actually dropped.
     *
     * The event is emitted only when the delete succeeded; if the token remains on disk, no event
     * is emitted and [SignOutResult] carries the durability outcome instead.
     *
     * [reason] tells the consumer why: an invalid refresh token from the server, or an explicit
     * user action. Network failures never produce this event.
     */
    data class SignedOut(
        val reason: SignOutReason,
    ) : SessionEvent
}

enum class SignOutReason {
    /** The server rejected the refresh token (`invalid_token`): the session cannot continue. */
    INVALID_TOKEN,

    /** The user asked to sign out. */
    USER_INITIATED,
}
