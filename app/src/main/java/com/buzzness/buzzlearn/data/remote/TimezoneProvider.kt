package com.buzzness.buzzlearn.data.remote

import java.time.ZoneId

/**
 * IANA timezone sent in request bodies (the contract has no timezone header).
 *
 * The injectable suppliers exist so the fallback can be unit-tested on the JVM without
 * depending on the host machine configuration.
 */
class TimezoneProvider(
    private val systemZoneId: () -> String = { ZoneId.systemDefault().id },
    private val availableZoneIds: () -> Set<String> = { ZoneId.getAvailableZoneIds() },
) {
    fun current(): String {
        val zoneId = systemZoneId()
        return if (zoneId in availableZoneIds()) zoneId else UTC
    }

    companion object {
        const val UTC = "UTC"
    }
}
