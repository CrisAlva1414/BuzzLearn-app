package com.buzzness.buzzlearn.data.remote.dto

private const val REDACTED = "<redacted>"

/**
 * Replaces a credential, token or PII value in DTO [toString] output. DTOs are easy to log by
 * accident (including nested inside an exception message), so their string form must never
 * reveal the value (RNF-14).
 */
internal fun redact(value: String): String = if (value.isEmpty()) value else REDACTED
