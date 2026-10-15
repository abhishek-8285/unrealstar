package com.unrealstar.live

import com.unrealstar.live.api.UnrealStarApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealStarApiClientTest {

    @Test
    fun testLocaliseWithNullOrBlank() {
        assertNull(UnrealStarApiClient.localise(null))
        assertNull(UnrealStarApiClient.localise(""))
        assertNull(UnrealStarApiClient.localise("   "))
    }

    @Test
    fun testLocaliseWithValidUrl() {
        val externalUrl = "https://cdn.camstar.live/assets/avatars/user_1.png"
        val localised = UnrealStarApiClient.localise(externalUrl)

        assertNotNull(localised)
        assertTrue(localised!!.endsWith("/assets/avatars/user_1.png"))
        // Base URL should replace the scheme/host
        assertTrue(localised.startsWith("http://") || localised.startsWith("https://"))
    }

    @Test
    fun testLocalisePreservesPath() {
        val externalUrl = "http://remote-server:8080/static/video/h264_stream.mp4"
        val localised = UnrealStarApiClient.localise(externalUrl)

        assertNotNull(localised)
        assertTrue(localised!!.endsWith("/static/video/h264_stream.mp4"))
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
