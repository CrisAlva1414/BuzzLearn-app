package com.buzzness.buzzlearn.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class TimezoneProviderTest {
    @Test
    fun returnsSystemZoneWhenItIsAnAvailableIanaZone() {
        val provider =
            TimezoneProvider(
                systemZoneId = { "America/Santiago" },
                availableZoneIds = { setOf("America/Santiago", "UTC") },
            )

        assertEquals("America/Santiago", provider.current())
    }

    @Test
    fun fallsBackToUtcWhenSystemZoneIsNotAvailable() {
        val provider =
            TimezoneProvider(
                systemZoneId = { "Not/AZone" },
                availableZoneIds = { setOf("America/Santiago", "UTC") },
            )

        assertEquals(TimezoneProvider.UTC, provider.current())
    }

    @Test
    fun defaultProviderReturnsTheHostZoneWhenItIsIanaValid() {
        val systemZone = ZoneId.systemDefault().id
        val provider = TimezoneProvider()

        val expected =
            if (systemZone in ZoneId.getAvailableZoneIds()) systemZone else TimezoneProvider.UTC

        assertEquals(expected, provider.current())
    }
}
