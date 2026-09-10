package com.pars.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class SPOR1 : MainAPI() {
    override var mainUrl = "https://tambettv23.com"
    override var name = "SPOR1"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true

    private val remoteConfigUrl =
        "https://raw.githubusercontent.com/punistek/Maydonoz/main/SPOR1/domains.json"

    data class SiteConfig(
        @JsonProperty("site") val site: String? = null
    )

    data class DomainResponse(
        @JsonProperty("baseurl") val baseurl: String? = null
    )

    private data class Endpoints(
        val site: String,
        val channelsUrl: String,
        val domainUrl: String
    )

    private data class Channel(
        val id: String,
        val title: String,
        val poster: String?
    )

    @Volatile
    private var cachedEndpoints: Endpoints? = null

    private fun normalizeSite(url: String): String =
        url.trim().trimEnd('/')

    private fun originOf(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Throwable) {
            null
        }
    }

    private fun absoluteUrl(base: String, value: String): String {
        val v = value.trim()
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        return if (v.startsWith("/")) {
            normalizeSite(base) + v
        } else {
            normalizeSite(base) + "/" + v
        }
    }

    private fun candidateSites(configured: String?): List<String> {
        val out = linkedSetOf<String>()

        fun add(url: String?) {
            val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
            out += normalizeSite(value)
        }

        add(configured)
        add(mainUrl)

        // Mevcut numaralı TamBet alan adının ileri sürümlerini otomatik dener.
        // 23 -> 24 -> 25 -> ... -> 40
        for (n in 23..40) {
            add("https://tambettv$n.com")
        }

        return out.toList()
    }

    private suspend fun configuredSite(): String? {
        return try {
            app.get(remoteConfigUrl, cacheTime = 0)
                .parsedSafe<SiteConfig>()
                ?.site
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractFetchUrl(html: String, fileName: String): String? {
        val regex = Regex(
            """fetch\(\s*['"]([^'"]*${Regex.escape(fileName)}[^'"]*)['"]""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun extractDomainUrl(html: String): String? {
        val regex = Regex(
            """domainUrl\s*:\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun looksLikeTamBet(html: String): Boolean {
        val text = html.lowercase()
        return (
            "channels.php" in text &&
            ("channel?id=" in text || "kanallar" in text || "domain.php" in text)
        )
    }

    private suspend fun resolveEndpoints(force: Boolean = false): Endpoints? {
        if (!force) cachedEndpoints?.let { return it }

        val configured = configuredSite()

        for (candidate in candidateSites(configured)) {
            try {
                val response = app.get(
                    candidate,
                    cacheTime = 0
                )
                val html = response.text
                if (!looksLikeTamBet(html)) continue

                val site = normalizeSite(candidate)

                val channelsRaw =
                    extractFetchUrl(html, "channels.php")
                        ?: "https://data-reality.com/channels.php"

                var domainRaw =
                    extractDomainUrl(html)

                if (domainRaw.isNullOrBlank()) {
                    // Ana sayfada yoksa player sayfasından çıkar.
                    val playerHtml = app.get(
                        "$site/channel?id=zirve",
                        referer = "$site/",
                        cacheTime = 0
                    ).text

                    domainRaw = extractDomainUrl(playerHtml)
                }

                val channelsUrl = absoluteUrl(site, channelsRaw)
                val domainUrl = absoluteUrl(
                    site,
                    domainRaw ?: "https://data-reality.com/domain.php"
                )

                val endpoints = Endpoints(
                    site = site,
                    channelsUrl = channelsUrl,
                    domainUrl = domainUrl
                )

                cachedEndpoints = endpoints
                mainUrl = site
                return endpoints
            } catch (_: Throwable) {
                // Sonraki aday domaine geç.
            }
        }

        return null
    }

    private fun Element.toChannel(site: String): Channel? {
        val href = attr("href")
        val id = Regex("""(?:\?|&)id=([^&]+)""")
            .find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val title = selectFirst(".teams .home")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val image = selectFirst(".teams .away img")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val poster = image?.let { absoluteUrl(site, it) }

        return Channel(
            id = id,
            title = title,
            poster = poster
        )
    }

    private suspend fun getChannels(forceResolve: Boolean = false): Pair<Endpoints, List<Channel>>? {
        val endpoints = resolveEndpoints(forceResolve) ?: return null

        return try {
            val response = app.get(
                endpoints.channelsUrl,
                referer = "${endpoints.site}/",
                cacheTime = 0
            )

            val document = Jsoup.parse(response.text, endpoints.channelsUrl)

            val channels = document
                .select("a.single-match[href*='channel?id=']")
                .mapNotNull { it.toChannel(endpoints.site) }
                .distinctBy { it.id }

            if (channels.isEmpty() && !forceResolve) {
                cachedEndpoints = null
                return getChannels(true)
            }

            endpoints to channels
        } catch (_: Throwable) {
            if (!forceResolve) {
                cachedEndpoints = null
                getChannels(true)
            } else {
                null
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val result = getChannels()
            ?: return newHomePageResponse(
                listOf(HomePageList("Kanallar", emptyList())),
                hasNext = false
            )

        val endpoints = result.first
        val channels = result.second

        val items = channels.map { channel ->
            newLiveSearchResponse(
                channel.title,
                "${endpoints.site}/channel?id=${channel.id}",
                TvType.Live
            ) {
                posterUrl = channel.poster
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Kanallar",
                    items,
                    isHorizontalImages = false
                )
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val result = getChannels() ?: return emptyList()
        val endpoints = result.first

        return result.second
            .filter { it.title.contains(q, ignoreCase = true) }
            .map { channel ->
                newLiveSearchResponse(
                    channel.title,
                    "${endpoints.site}/channel?id=${channel.id}",
                    TvType.Live
                ) {
                    posterUrl = channel.poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""(?:\?|&)id=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val result = getChannels()
        val endpoints = result?.first ?: resolveEndpoints() ?: return null
        val channel = result?.second?.firstOrNull { it.id == id }

        val title = channel?.title ?: id.uppercase()
        val dataUrl = "${endpoints.site}/channel?id=$id"

        return newLiveStreamLoadResponse(
            title,
            dataUrl,
            dataUrl
        ) {
            posterUrl = channel?.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:\?|&)id=([^&]+)""")
            .find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        suspend fun buildLink(force: Boolean): Boolean {
            val endpoints = resolveEndpoints(force) ?: return false

            val domain = try {
                app.get(
                    endpoints.domainUrl,
                    referer = "${endpoints.site}/",
                    cacheTime = 0
                ).parsedSafe<DomainResponse>()
                    ?.baseurl
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            } ?: return false

            val baseUrl = if (domain.endsWith("/")) domain else "$domain/"
            val streamUrl = "${baseUrl}${id}/mono.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "${endpoints.site}/"
                    headers = mapOf(
                        "Origin" to endpoints.site,
                        "Referer" to "${endpoints.site}/"
                    )
                    quality = Qualities.Unknown.value
                }
            )

            return true
        }

        if (buildLink(false)) return true

        // Endpoint veya ana site değişmişse cache'i temizleyip bir kez yeniden çöz.
        cachedEndpoints = null
        return buildLink(true)
    }
}
