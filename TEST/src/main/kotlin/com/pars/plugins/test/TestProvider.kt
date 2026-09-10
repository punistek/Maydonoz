package com.pars.plugins.test

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class TestProvider : MainAPI() {

    override var mainUrl = "https://patronlig20.cfd"
    override var name = "TEST"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    private val dataHost = "https://patronsports2.cfd"
    private val channelsUrl = "$dataHost/channels.php"
    private val matchesUrl = "$dataHost/matches.php"
    private val domainUrl = "$dataHost/domain.php"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36"

    private val dataHeaders: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json,text/plain,*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Origin" to mainUrl,
            "Pragma" to "no-cache",
            "Referer" to "$mainUrl/",
            "User-Agent" to userAgent,
        )

    override val mainPage = mainPageOf(
        "channels" to "7/24 Kanallar",
        "matches" to "Maçlar",
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
        val playerPage = "$mainUrl/ch.html?id=${urlEncode(channelId)}"

        val streamHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Origin" to mainUrl,
            "Pragma" to "no-cache",
            "Referer" to playerPage,
            "User-Agent" to userAgent,
        )

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                referer = playerPage
                headers = streamHeaders
                quality = Qualities.Unknown.value
            },
        )

        return true
    }

    private suspend fun resolveStreamBaseUrl(): String? {
        val response = app.get(
            domainUrl,
            headers = dataHeaders,
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
        val text = app.get(
            channelsUrl,
            headers = dataHeaders,
        ).text.trim()

        val array = parseArray(text, "channels") ?: return emptyList()
        val result = ArrayList<SearchResponse>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val streamSource = item.optString("URL", "").trim()
            val channelId = extractChannelId(streamSource) ?: continue

            val title = firstNonBlank(
                item.optString("Mac", ""),
                item.optString("name", ""),
                item.optString("Name", ""),
                item.optString("channel_name", ""),
            ) ?: "Kanal ${i + 1}"

            val poster = normalizeAssetUrl(
                firstNonBlank(
                    item.optString("Logo", ""),
                    item.optString("logo", ""),
                    item.optString("poster", ""),
                ),
            )

            result += newLiveSearchResponse(
                title,
                buildLoadUrl(channelId, title, poster),
                TvType.Live,
            ) {
                posterUrl = poster
            }
        }

        return result.distinctBy { it.url }
    }

    private suspend fun loadMatchItems(): List<SearchResponse> {
        val text = app.get(
            matchesUrl,
            headers = dataHeaders,
        ).text.trim()

        val array = parseArray(text, "matches") ?: return emptyList()
        val result = ArrayList<SearchResponse>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val streamSource = item.optString("URL", "").trim()
            val channelId = extractChannelId(streamSource) ?: continue

            val home = firstNonBlank(
                item.optString("HomeTeam", ""),
                item.optString("home", ""),
            ).orEmpty()

            val away = firstNonBlank(
                item.optString("AwayTeam", ""),
                item.optString("away", ""),
            ).orEmpty()

            val matchName = firstNonBlank(
                item.optString("Mac", ""),
                item.optString("name", ""),
            ).orEmpty()

            val time = firstNonBlank(
                item.optString("Time", ""),
                item.optString("time", ""),
            ).orEmpty()

            val league = firstNonBlank(
                item.optString("league", ""),
                item.optString("League", ""),
            ).orEmpty()

            val title = when {
                home.isNotEmpty() && away.isNotEmpty() -> "$home - $away"
                matchName.isNotEmpty() -> matchName
                else -> "Canlı Maç ${i + 1}"
            }

            val displayTitle = buildString {
                append(title)
                if (time.isNotEmpty()) append(" • ").append(time)
                if (league.isNotEmpty()) append(" • ").append(league)
            }

            val poster = normalizeAssetUrl(
                firstNonBlank(
                    item.optString("Logo", ""),
                    item.optString("logo", ""),
                    item.optString("HomeLogo", ""),
                ),
            )

            result += newLiveSearchResponse(
                displayTitle,
                buildLoadUrl(channelId, displayTitle, poster),
                TvType.Live,
            ) {
                posterUrl = poster
            }
        }

        return result.distinctBy { it.url }
    }

    private fun parseArray(text: String, objectKey: String): JSONArray? {
        if (text.isBlank()) return null

        return runCatching {
            when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> {
                    val root = JSONObject(text)
                    root.optJSONArray(objectKey)
                        ?: root.optJSONArray("data")
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun extractChannelId(source: String): String? {
        if (source.isBlank()) return null

        val decoded = runCatching { urlDecode(source) }.getOrDefault(source)

        val fromQuery = Regex("[?&]id=([^&#]+)", RegexOption.IGNORE_CASE)
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (fromQuery != null) {
            return fromQuery.replace(Regex("[^A-Za-z0-9_-]"), "")
                .takeIf { it.isNotEmpty() }
        }

        // Endpoint doğrudan kanal ID'si döndürürse onu da kabul et.
        if (!decoded.contains("/") && !decoded.contains("?") && !decoded.contains("&")) {
            return decoded.replace(Regex("[^A-Za-z0-9_-]"), "")
                .takeIf { it.isNotEmpty() }
        }

        return null
    }

    private fun normalizeAssetUrl(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$dataHost$raw"
            else -> "$dataHost/$raw"
        }
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() }?.trim()

    private fun buildLoadUrl(
        channelId: String,
        title: String,
        poster: String?,
    ): String {
        return buildString {
            append(mainUrl)
            append("/ch.html?id=")
            append(urlEncode(channelId))
            append("&title=")
            append(urlEncode(title))

            if (!poster.isNullOrBlank()) {
                append("&poster=")
                append(urlEncode(poster))
            }
        }
    }

    private fun getQueryParameter(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null

        return query
            .split('&')
            .firstNotNullOfOrNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size == 2 && pair[0].equals(key, ignoreCase = true)) {
                    pair[1]
                } else {
                    null
                }
            }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
