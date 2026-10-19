package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {
    @Test
    fun defaultStartsWithGithubAndFallsBackToMirrors() {
        val sources = UpdateChannels.sources(UpdateChannels.GITHUB)
        assertEquals(UpdateClient.GITHUB_RELEASES_URL, sources.first().releasesUrl)
        assertEquals("https://gh-proxy.com/", sources[1].assetPrefix)
        assertEquals("https://ghproxy.net/", sources[2].assetPrefix)
    }

    @Test
    fun mirrorChoiceStartsWithMirrorAndFallsBackToGithub() {
        val sources = UpdateChannels.sources(UpdateChannels.MIRROR_GH_PROXY)
        assertTrue(sources.first().releasesUrl.startsWith("https://gh-proxy.com/https://api.github.com/"))
        assertEquals(UpdateClient.GITHUB_RELEASES_URL, sources[1].releasesUrl)
    }

    @Test
    fun mirrorAppliesToMetadataAndAssets() {
        val mirror = UpdateChannels.sources(UpdateChannels.MIRROR_GH_PROXY_NET).first()
        assertEquals(
            "https://ghproxy.net/https://example.com/DiPlay.apk",
            mirror.assetUrl("https://example.com/DiPlay.apk"),
        )
    }
}
