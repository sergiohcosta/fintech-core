package com.fintech.mobile.core.network

import org.junit.Test
import kotlin.test.assertEquals

class EnvironmentUrlResolverTest {

    @Test
    fun `dev over LAN resolves to the kafofao host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.DEV, NetworkRoute.LAN, null)
        assertEquals("http://fintech-core-dev.kafofao/", result)
    }

    @Test
    fun `dev over Tailscale resolves to the ts-net host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.DEV, NetworkRoute.TAILSCALE, null)
        assertEquals("https://fintech-core-dev.atlas-haddock.ts.net/", result)
    }

    @Test
    fun `hmg over LAN resolves to the kafofao host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.HMG, NetworkRoute.LAN, null)
        assertEquals("http://fintech-core-hmg.kafofao/", result)
    }

    @Test
    fun `hmg over Tailscale resolves to the ts-net host`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.HMG, NetworkRoute.TAILSCALE, null)
        assertEquals("https://fintech-core-hmg.atlas-haddock.ts.net/", result)
    }

    @Test
    fun `prod over LAN resolves to the kafofao host without a suffix`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.PROD, NetworkRoute.LAN, null)
        assertEquals("http://fintech-core.kafofao/", result)
    }

    @Test
    fun `prod over Tailscale resolves to the ts-net host without a suffix`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.PROD, NetworkRoute.TAILSCALE, null)
        assertEquals("https://fintech-core.atlas-haddock.ts.net/", result)
    }

    @Test
    fun `local without a custom URL defaults to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, null)
        assertEquals("http://10.0.2.2:8080/", result)
    }

    @Test
    fun `local with a blank custom URL defaults to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "   ")
        assertEquals("http://10.0.2.2:8080/", result)
    }

    @Test
    fun `local with a custom URL missing scheme and trailing slash is normalized`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "192.168.1.50:8080")
        assertEquals("http://192.168.1.50:8080/", result)
    }

    @Test
    fun `local with a custom URL already well formed is unchanged`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "https://192.168.1.50:8443/")
        assertEquals("https://192.168.1.50:8443/", result)
    }

    @Test
    fun `local ignores the route parameter`() {
        val lan = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "192.168.1.50:8080")
        val tailscale = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.TAILSCALE, "192.168.1.50:8080")
        assertEquals(lan, tailscale)
    }

    @Test
    fun `local with only a scheme falls back to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "http://")
        assertEquals("http://10.0.2.2:8080/", result)
    }

    @Test
    fun `local with a space in the host falls back to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "abc def")
        assertEquals("http://10.0.2.2:8080/", result)
    }

    @Test
    fun `local with a port out of range falls back to the emulator alias`() {
        val result = EnvironmentUrlResolver.resolveBaseUrl(Environment.LOCAL, NetworkRoute.LAN, "10.0.2.2:99999")
        assertEquals("http://10.0.2.2:8080/", result)
    }
}
