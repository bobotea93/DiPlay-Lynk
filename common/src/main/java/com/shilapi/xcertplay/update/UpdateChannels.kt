package com.shilapi.xcertplay.update

/**
 * Update sources. The first entry is the driver's choice; the rest are fallbacks.
 *
 * The mirror entries prefix both the GitHub API URL and every release asset URL with the
 * mirror host, so metadata and APK downloads use the same route.
 */
internal object UpdateChannels {
    data class Source(val releasesUrl: String, val assetPrefix: String = "") {
        fun assetUrl(url: String): String = assetPrefix + url
    }

    const val GITHUB = 0
    const val MIRROR_GH_PROXY = 1
    const val MIRROR_GH_PROXY_NET = 2
    const val DEFAULT = GITHUB

    private val github = Source(UpdateClient.GITHUB_RELEASES_URL)
    private val mirrorOne = Source(
        "https://gh-proxy.com/${UpdateClient.GITHUB_RELEASES_URL}",
        "https://gh-proxy.com/",
    )
    private val mirrorTwo = Source(
        "https://ghproxy.net/${UpdateClient.GITHUB_RELEASES_URL}",
        "https://ghproxy.net/",
    )

    fun sources(choice: Int): List<Source> = when (choice) {
        MIRROR_GH_PROXY -> listOf(mirrorOne, github, mirrorTwo)
        MIRROR_GH_PROXY_NET -> listOf(mirrorTwo, github, mirrorOne)
        else -> listOf(github, mirrorOne, mirrorTwo)
    }
}
