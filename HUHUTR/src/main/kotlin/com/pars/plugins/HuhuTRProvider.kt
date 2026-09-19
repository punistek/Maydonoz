package com.pars.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class HuhuTRProvider : MainAPI() {
    override var mainUrl = "https://huhu.to"
    override var name = "HUHU Türkiye"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val mapper = ObjectMapper()

    private val requestHeaders = mapOf(
        "Accept" to "*/*",
        "Content-Type" to "application/json; charset=utf-8",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/live",
        "User-Agent" to USER_AGENT,
    )

    override val mainPage = mainPageOf(
        "turkey" to "Türkiye Kanalları"
    )

    private data class HuhuChannel(
        val id: String,
        val name: String,
        val poster: String? = null,
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        // HUHU'nun Türkiye kataloğu. Başka ülke/grup istenmez.
        val channels = fetchTurkeyCatalog(search = "")
        val results = channels.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch?live=${channel.id}",
                TvType.Live,
                fix = false,
            ) {
                posterUrl = channel.poster
                lang = "tr"
            }
        }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()

        // Arama da yalnız Turkey filtresiyle yapılır.
        return fetchTurkeyCatalog(search = clean).map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch?live=${channel.id}",
                TvType.Live,
                fix = false,
            ) {
                posterUrl = channel.poster
                lang = "tr"
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun load(url: String): LoadResponse {
        val id = extractChannelId(url)
            ?: throw ErrorLoadingException("HUHU kanal kimliği bulunamadı")

        val channel = fetchTurkeyCatalog(search = "")
            .firstOrNull { it.id == id }

        val title = channel?.name ?: "HUHU Canlı TV"

        return newLiveStreamLoadResponse(
            name = title,
            url = "$mainUrl/watch?live=$id",
            dataUrl = id,
        ) {
            posterUrl = channel?.poster
            plot = "HUHU Türkiye canlı yayını"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
    ): Boolean {
        val id = extractChannelId(data) ?: data.trim()
        if (id.isEmpty()) return false

        val watchUrl = "$mainUrl/watch?live=$id"
        val payload = mapOf(
            "language" to "de",
            "region" to "DE",
            "url" to "$mainUrl/huhu-iptv/play/$id",
        )

        val response = app.post(
            "$mainUrl/mediaurl-resolve.json",
            headers = requestHeaders + ("Referer" to watchUrl),
            json = payload,
        )

        if (!response.isSuccessful) {
            throw ErrorLoadingException(
                "HUHU resolver HTTP ${response.code}"
            )
        }

        val streamUrl = extractResolvedStream(response.text)
            ?: throw ErrorLoadingException("HUHU HLS adresi çözülemedi")

        callback(
            newExtractorLink(
                source = name,
                name = "HUHU Türkiye",
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                )
            }
        )

        return true
    }

    private suspend fun fetchTurkeyCatalog(search: String): List<HuhuChannel> {
        val payload = mapOf(
            "language" to "de",
            "region" to "DE",
            "catalogId" to "iptv",
            "id" to "",
            "adult" to false,
            "search" to search,
            "sort" to "trending-region",
            "filter" to mapOf("group" to "Turkey"),
            "cursor" to null,
        )

        val response = app.post(
            "$mainUrl/mediaurl-catalog.json",
            headers = requestHeaders,
            json = payload,
        )

        if (!response.isSuccessful) {
            throw ErrorLoadingException(
                "HUHU katalog HTTP ${response.code}"
            )
        }

        return parseCatalog(response.text)
    }

    /**
     * HUHU katalog JSON'u sürüm değiştirirse tek bir sabit JSON path'e
     * bağımlı kalmamak için nesneleri dolaşır. Yalnız geçerli kanal kimliği
     * + kanal adı bulunan kayıtlar kabul edilir.
     */
    private fun parseCatalog(raw: String): List<HuhuChannel> {
        val root = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return emptyList()

        val result = LinkedHashMap<String, HuhuChannel>()

        fun walk(node: JsonNode) {
            when {
                node.isObject -> {
                    val id = firstText(
                        node,
                        "id",
                        "channelId",
                        "channel_id",
                        "mediaId",
                        "media_id",
                    )?.let(::normalizeId)

                    val title = firstText(
                        node,
                        "name",
                        "title",
                        "channelName",
                        "channel_name",
                    )?.trim()

                    val poster = firstText(
                        node,
                        "poster",
                        "posterUrl",
                        "poster_url",
                        "logo",
                        "logoUrl",
                        "logo_url",
                        "image",
                    )?.trim()?.takeIf { it.startsWith("http") }

                    if (!id.isNullOrBlank() && !title.isNullOrBlank()) {
                        result.putIfAbsent(
                            id,
                            HuhuChannel(id = id, name = title, poster = poster)
                        )
                    }

                    val fields = node.fields()
                    while (fields.hasNext()) {
                        walk(fields.next().value)
                    }
                }

                node.isArray -> node.forEach(::walk)
            }
        }

        walk(root)

        // API şekli değişse bile play/<id> bulunan kayıtları son çare yakala.
        if (result.isEmpty()) {
            val playRegex = Regex(
                """huhu-iptv/play/([A-Za-z0-9_-]+)"""
            )
            playRegex.findAll(raw).forEachIndexed { index, match ->
                val id = match.groupValues[1]
                result.putIfAbsent(
                    id,
                    HuhuChannel(id, "HUHU Kanal ${index + 1}")
                )
            }
        }

        return result.values.toList()
    }

    private fun firstText(node: JsonNode, vararg keys: String): String? {
        for (key in keys) {
            val value = node.get(key) ?: continue
            if (value.isTextual || value.isNumber) {
                val text = value.asText().trim()
                if (text.isNotEmpty() && text != "null") return text
            }
        }
        return null
    }

    private fun normalizeId(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        Regex("""huhu-iptv/play/([A-Za-z0-9_-]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        Regex("""[?&]live=([A-Za-z0-9_-]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        // HUHU örneğindeki kimlikler hex-benzeri uzun tokenlardır.
        return value.takeIf {
            it.length >= 8 &&
                it.matches(Regex("""[A-Za-z0-9_-]+"""))
        }
    }

    private fun extractChannelId(value: String): String? {
        Regex("""[?&]live=([A-Za-z0-9_-]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        Regex("""huhu-iptv/play/([A-Za-z0-9_-]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        return value.trim().takeIf {
            it.length >= 8 &&
                it.matches(Regex("""[A-Za-z0-9_-]+"""))
        }
    }

    private fun extractResolvedStream(raw: String): String? {
        val root = runCatching { mapper.readTree(raw) }.getOrNull()

        if (root != null) {
            val urls = ArrayList<String>()

            fun walk(node: JsonNode) {
                when {
                    node.isTextual -> {
                        val value = node.asText().trim()
                        if (value.startsWith("http")) urls += value
                    }

                    node.isObject -> {
                        val fields = node.fields()
                        while (fields.hasNext()) {
                            walk(fields.next().value)
                        }
                    }

                    node.isArray -> node.forEach(::walk)
                }
            }

            walk(root)

            urls.firstOrNull {
                it.contains(".m3u8", ignoreCase = true)
            }?.let { return it }

            urls.firstOrNull {
                it.startsWith("http") &&
                    (it.contains("/hls/", ignoreCase = true) ||
                     it.contains("sunshine", ignoreCase = true))
            }?.let { return it }
        }

        // JSON içinde escape edilmiş URL ihtimali.
        val cleaned = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        return Regex("""https?://[^\s"'\\]+?\.m3u8(?:\?[^\s"'\\]*)?""")
            .find(cleaned)
            ?.value
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"
    }
}
