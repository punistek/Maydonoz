package com.pars.plugins.test2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class TestProvider : MainAPI() {

    override var mainUrl = "https://www.betmarinotv1101.site"
    override var name = "TEST2"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    private val dataHost = "https://data-reality.com"
    private val channelsUrl = "$dataHost/channels.php"
    private val matchesUrl = "$dataHost/matches.php"
    private val domainUrl = "$dataHost/domain.php"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36"

    private val browserHeaders: Map<String, String>
        get() = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Origin" to mainUrl,
            "Pragma" to "no-cache",
            "Priority" to "u=1, i",
            "Referer" to "$mainUrl/",
            "Sec-CH-UA" to "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"",
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to userAgent,
        )

    override val mainPage = mainPageOf(
        "channels" to "Canlı Kanallar",
        "matches" to "Canlı Maçlar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val items = when (request.data) {
            "matches" -> loadMatchItems()
            else -> loadChannelItems()
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        return (loadChannelItems() + loadMatchItems())
            .distinctBy { it.url }
            .filter { it.name.contains(q, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = getQueryParameter(url, "id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("Kanal kimliği bulunamadı")

        val title = getQueryParameter(url, "title")
            ?.let(::urlDecode)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Canlı Yayın"

        val poster = getQueryParameter(url, "poster")
            ?.let(::urlDecode)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return newLiveStreamLoadResponse(
            title,
            url,
            url,
        ) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val rawId = getQueryParameter(data, "id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val channelId = rawId.replace(Regex("[^A-Za-z0-9_-]"), "")
        if (channelId.isEmpty()) return false

        val baseUrl = resolveStreamBaseUrl() ?: return false
        val streamUrl = "${baseUrl.trimEnd('/')}/$channelId/mono.m3u8"
        val playerPage = "$mainUrl/channel?id=${urlEncode(channelId)}"

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                // KRİTİK:
                // Media3 açamazsa uygulamadaki generic WebView fallback gerçek
                // kanal sayfasını buradan öğrenir. Ana sayfa (mainUrl/) verilirse
                // fallback hangi kanalı açacağını bilemez.
                referer = playerPage
                headers = browserHeaders + mapOf(
                    "Referer" to playerPage,
                    "Origin" to mainUrl,
                )
                quality = Qualities.Unknown.value
            },
        )

        return true
    }

    private suspend fun resolveStreamBaseUrl(): String? {
        val response = app.get(
            domainUrl,
            headers = browserHeaders,
        )

        val text = response.text.trim()
        if (text.isEmpty()) return null

        return runCatching {
            JSONObject(text)
                .optString("baseurl", "")
                .trim()
                .takeIf {
                    it.startsWith("http://", ignoreCase = true) ||
                        it.startsWith("https://", ignoreCase = true)
                }
        }.getOrNull()
    }

    private suspend fun loadChannelItems(): List<SearchResponse> {
        val document = app.get(
            channelsUrl,
            headers = browserHeaders,
        ).document

        return document
            .select("a.single-match[href*='channel?id=']")
            .mapNotNull(::channelElementToSearchResponse)
            .distinctBy { it.url }
    }

    private suspend fun loadMatchItems(): List<SearchResponse> {
        val document = app.get(
            matchesUrl,
            headers = browserHeaders,
        ).document

        return document
            .select("a.single-match[href*='channel?id=']")
            .mapNotNull(::matchElementToSearchResponse)
            .distinctBy { it.url }
    }

    private fun channelElementToSearchResponse(element: Element): SearchResponse? {
        val channelId = extractChannelId(element.attr("href")) ?: return null

        val title = element
            .selectFirst(".teams .home")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val poster = element
            .selectFirst(".teams .away img")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::absoluteSiteUrl)

        return newLiveSearchResponse(
            title,
            buildLoadUrl(channelId, title, poster),
            TvType.Live,
        ) {
            posterUrl = poster
        }
    }

    private fun matchElementToSearchResponse(element: Element): SearchResponse? {
        val channelId = extractChannelId(element.attr("href")) ?: return null

        val home = element.selectFirst(".teams .home")?.text()?.trim().orEmpty()
        val away = element.selectFirst(".teams .away")?.text()?.trim().orEmpty()
        val sport = element.selectFirst(".date")?.text()?.trim().orEmpty()
        val event = element.selectFirst(".event")?.text()?.trim().orEmpty()

        val title = when {
            home.isNotEmpty() && away.isNotEmpty() -> "$home - $away"
            home.isNotEmpty() -> home
            event.isNotEmpty() -> event
            else -> return null
        }

        val poster = element
            .selectFirst("img")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::absoluteSiteUrl)

        val displayTitle = buildString {
            append(title)
            if (event.isNotEmpty()) append(" • ").append(event)
            if (sport.isNotEmpty() && !event.contains(sport, ignoreCase = true)) {
                append(" • ").append(sport)
            }
        }

        return newLiveSearchResponse(
            displayTitle,
            buildLoadUrl(channelId, displayTitle, poster),
            TvType.Live,
        ) {
            posterUrl = poster
        }
    }

    private fun buildLoadUrl(
        channelId: String,
        title: String,
        poster: String?,
    ): String {
        return buildString {
            append(mainUrl)
            append("/channel?id=")
            append(urlEncode(channelId))
            append("&title=")
            append(urlEncode(title))

            if (!poster.isNullOrBlank()) {
                append("&poster=")
                append(urlEncode(poster))
            }
        }
    }

    private fun extractChannelId(href: String): String? {
        val value = Regex("""(?:\?|&)id=([^&#"' ]+)""")
            .find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return urlDecode(value)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun absoluteSiteUrl(src: String): String {
        return when {
            src.startsWith("https://", ignoreCase = true) -> src
            src.startsWith("http://", ignoreCase = true) -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "$mainUrl$src"
            else -> "$mainUrl/$src"
        }
    }

    private fun getQueryParameter(
        url: String,
        key: String,
    ): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null

        return query
            .split('&')
            .firstOrNull { part ->
                part.substringBefore('=', "") == key
            }
            ?.substringAfter('=', "")
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun urlDecode(value: String): String =
        runCatching {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)
}
