package com.fintech.mobile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvironmentPreferencesTest {

    private fun newPreferences(): EnvironmentPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return EnvironmentPreferences(context)
    }

    @Test
    fun `defaults to LOCAL, LAN and no custom URL`() {
        val preferences = newPreferences()

        assertEquals(Environment.LOCAL, preferences.environment.value)
        assertEquals(NetworkRoute.LAN, preferences.route.value)
        assertNull(preferences.customLocalUrl.value)
    }

    @Test
    fun `setEnvironment updates the StateFlow and survives a new instance`() {
        val preferences = newPreferences()

        preferences.setEnvironment(Environment.HMG)

        assertEquals(Environment.HMG, preferences.environment.value)
        assertEquals(Environment.HMG, newPreferences().environment.value)
    }

    @Test
    fun `setRoute updates the StateFlow and survives a new instance`() {
        val preferences = newPreferences()

        preferences.setRoute(NetworkRoute.TAILSCALE)

        assertEquals(NetworkRoute.TAILSCALE, preferences.route.value)
        assertEquals(NetworkRoute.TAILSCALE, newPreferences().route.value)
    }

    @Test
    fun `setCustomLocalUrl updates the StateFlow and survives a new instance`() {
        val preferences = newPreferences()

        preferences.setCustomLocalUrl("192.168.1.50:8080")

        assertEquals("192.168.1.50:8080", preferences.customLocalUrl.value)
        assertEquals("192.168.1.50:8080", newPreferences().customLocalUrl.value)
    }

    @Test
    fun `currentBaseUrl delegates to EnvironmentUrlResolver with the stored selection`() {
        val preferences = newPreferences()
        preferences.setEnvironment(Environment.DEV)
        preferences.setRoute(NetworkRoute.TAILSCALE)

        assertEquals("https://fintech-core-dev.atlas-haddock.ts.net/", preferences.currentBaseUrl())
    }
}
