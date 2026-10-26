package com.buzzness.buzzlearn.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {
    @Test
    fun baseUrlRejectionMessageDoesNotEchoTheValue() {
        val baseUrl = "https://probe-host.invalid/api"
        val error = runCatching { NetworkModule.createRetrofit(baseUrl) }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException but got $error", error is IllegalArgumentException)
        val message = error?.message
        assertNotNull(message)
        assertTrue(message.orEmpty().contains("must end with '/'"))
        assertFalse("Validation message must not echo the base URL", message.orEmpty().contains(baseUrl))
        assertFalse("Validation message must not echo the host", message.orEmpty().contains("probe-host"))
    }

    @Test
    fun invalidBaseUrlIsRejectedWithoutEchoingTheValue() {
        val baseUrl = "not-a-valid-url-probe"
        val error = runCatching { NetworkModule.createRetrofit(baseUrl) }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException but got $error", error is IllegalArgumentException)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("valid http(s) URL"))
        assertFalse("Validation message must not echo the base URL", message.contains(baseUrl))
    }

    @Test
    fun nonHttpSchemeIsRejectedWithoutEchoingTheValue() {
        val baseUrl = "ftp://probe-host.invalid/"
        val error = runCatching { NetworkModule.createRetrofit(baseUrl) }.exceptionOrNull()

        assertTrue("Expected IllegalArgumentException but got $error", error is IllegalArgumentException)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("valid http(s) URL"))
        assertFalse("Validation message must not echo the host", message.contains("probe-host"))
    }
}
