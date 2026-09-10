package com.pars.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class SPOR1 : MainAPI() {
    override var mainUrl = "https://tambettv23.com"
    override var name = "SPOR1"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true

    private val dataHost = "https://data-reality.com"

    data class DomainResponse(
        @JsonProperty("baseurl") val baseurl: String? = null
    )

    private data class Channel(
        val id: String,
        val title: String,
        val poster: String?
    )

    private fun Element.toChannel(): Channel? {
        val href = attr("href")
        val id = Regex("""(?:\?|&)id=([^&]+)""").find(href)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val title = selectFirst(".teams .home")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val image = selectFirst(".teams .away img")?.attr("src")?.trim()
        val poster = image?.takeIf { it.isNotEmpty() }?.let {
            when {
                it.startsWith("http://") || it.startsWith("https://") -> it
                it.startsWith("/") -> "$dataHost$it"
                else -> "$dataHost/$it"
            }
        }

        return Channel(id, title, poster)
    }

    private suspend fun getChannels(): List<Channel> {
        val document = app.get(
            "$dataHost/channels.php",
            referer = "$mainUrl/"
        ).document

        return document
            .select("a.single-match[href*='channel?id=']")
            .mapNotNull { it.toChannel() }
            .distinctBy { it.id }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val channels = getChannels()

        val items = channels.map { channel ->
            newLiveSearchResponse(
                channel.title,
                "$mainUrl/channel?id=${channel.id}",
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

        return getChannels()
            .filter { it.title.contains(q, ignoreCase = true) }
            .map { channel ->
                newLiveSearchResponse(
                    channel.title,
                    "$mainUrl/channel?id=${channel.id}",
                    TvType.Live
                ) {
                    posterUrl = channel.poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""(?:\?|&)id=([^&]+)""").find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val channel = getChannels().firstOrNull { it.id == id }
        val title = channel?.title ?: id.uppercase()

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            posterUrl = channel?.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:\?|&)id=([^&]+)""").find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val domain = app.get(
            "$dataHost/domain.php",
            referer = "$mainUrl/"
        ).parsedSafe<DomainResponse>()?.baseurl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val baseUrl = if (domain.endsWith("/")) domain else "$domain/"
        val streamUrl = "${baseUrl}${id}/mono.m3u8"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
