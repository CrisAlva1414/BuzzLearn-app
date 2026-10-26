package com.buzzness.buzzlearn.data.remote

/**
 * Stable error codes from the OpenAPI contract (`components.schemas.Error`).
 *
 * Unknown codes map to [UNKNOWN] instead of throwing, so a newer server contract never
 * crashes an older client.
 */
enum class ApiErrorCode(
    val wireValue: String,
) {
    VALIDATION_ERROR("validation_error"),
    EMAIL_ALREADY_REGISTERED("email_already_registered"),
    NICKNAME_TAKEN("nickname_taken"),
    UNDERAGE("underage"),
    PRIVACY_NOT_ACCEPTED("privacy_not_accepted"),
    WEAK_PASSWORD("weak_password"),
    INVALID_CREDENTIALS("invalid_credentials"),
    INVALID_TOKEN("invalid_token"),
    INVALID_CODE("invalid_code"),
    CODE_EXPIRED("code_expired"),
    EMAIL_NOT_VERIFIED("email_not_verified"),
    TOO_MANY_REQUESTS("too_many_requests"),
    NOT_FOUND("not_found"),
    LESSON_LOCKED("lesson_locked"),
    LESSON_CHANGED("lesson_changed"),
    INVALID_SUBMISSION("invalid_submission"),
    COURSE_NOT_COMPLETED("course_not_completed"),
    UNKNOWN("unknown"),
    ;

    companion object {
        private val byWireValue: Map<String, ApiErrorCode> =
            entries.associateBy { it.wireValue }

        fun fromWireValue(value: String?): ApiErrorCode = byWireValue[value] ?: UNKNOWN
    }
}
