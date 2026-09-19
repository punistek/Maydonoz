package com.pars.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class HuhuTRProvider : MainAPI() {
    override var mainUrl = "https://huhu.to"
    override var name = "HUHU Türkiye"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val mapper = ObjectMapper()

    private data class Channel(
        val id: String,
        val name: String,
        val url: String,
        val logo: String?
    )

    private data class CatalogPage(
        val items: List<Channel>,
        val nextCursor: Int?
    )

    override val mainPage = mainPageOf(
        "turkey" to "Türkiye Kanalları"
    )

    private fun headers(referer: String = "$mainUrl/live") = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Content-Type" to "application/json; charset=utf-8",
        "Origin" to mainUrl,
        "Referer" to referer,
        "User-Agent" to USER_AGENT
    )

    /*
     * ÖNEMLİ:
     * CloudStream sayfalamasına güvenmiyoruz.
     * HUHU'nun cursor zincirini TEK getMainPage çağrısında sonuna kadar geziyoruz.
     *
     * null -> 300 -> ... -> null
     *
     * Böylece HUHU web sayfasındaki Turkey listesinin tamamı tek kategoriye gelir.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val channels = fetchAllTurkeyChannels()

        val results = channels.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch?live=${channel.id}",
                TvType.Live,
                fix = false
            ) {
                posterUrl = channel.logo
            }
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // Tüm Turkey listesini alıp yerelde filtreliyoruz.
        // Böylece HUHU search endpoint'inin cursor davranışına bağlı kalmıyoruz.
        return fetchAllTurkeyChannels()
            .filter { it.name.contains(q, ignoreCase = true) }
            .map { channel ->
                newLiveSearchResponse(
                    channel.name,
                    "$mainUrl/watch?live=${channel.id}",
                    TvType.Live,
                    fix = false
                ) {
                    posterUrl = channel.logo
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun load(url: String): LoadResponse {
        val id = extractId(url)
            ?: throw ErrorLoadingException("HUHU kanal ID bulunamadı")

        // Kanal adını tekrar katalogdan buluyoruz.
        val channel = fetchAllTurkeyChannels().firstOrNull { it.id == id }
        val title = channel?.name ?: "HUHU Canlı TV"

        return newLiveStreamLoadResponse(
            title,
            "$mainUrl/watch?live=$id",
            id
        ) {
            posterUrl = channel?.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = extractId(data)
            ?: throw ErrorLoadingException("HUHU kanal ID bulunamadı")

        val watchUrl = "$mainUrl/watch?live=$id"

        val payload = mapOf(
            "language" to "de",
            "region" to "DE",
            "url" to "$mainUrl/huhu-iptv/play/$id"
        )

        val response = app.post(
            "$mainUrl/mediaurl-resolve.json",
            headers = headers(watchUrl),
            json = payload
        )

        val streamUrl = findM3u8(response.text)
            ?: throw ErrorLoadingException("HUHU HLS adresi çözülemedi")

        callback(
            newExtractorLink(
                source = name,
                name = "HUHU Türkiye",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
            }
        )
        return true
    }

    private suspend fun fetchAllTurkeyChannels(): List<Channel> {
        val all = LinkedHashMap<String, Channel>()
        var cursor: Int? = null
        val seenCursors = HashSet<Int?>()

        // Koruma: bozuk bir sunucu aynı cursor'u tekrar döndürürse sonsuz döngüye girmez.
        repeat(MAX_CATALOG_PAGES) {
            if (!seenCursors.add(cursor)) return@repeat

            val catalog = requestCatalog(cursor)

            for (channel in catalog.items) {
                all.putIfAbsent(channel.id, channel)
            }

            val next = catalog.nextCursor
            if (next == null) {
                return all.values.toList()
            }

            if (next == cursor) {
                return all.values.toList()
            }

            cursor = next
        }

        return all.values.toList()
    }

    private suspend fun requestCatalog(cursor: Int?): CatalogPage {
        val payload = mapOf(
            "language" to "de",
            "region" to "DE",
            "catalogId" to "iptv",
            "id" to "",
            "adult" to false,
            "search" to "",
            "sort" to "trending-region",
            "filter" to mapOf("group" to "Turkey"),
            "cursor" to cursor
        )

        val response = app.post(
            "$mainUrl/mediaurl-catalog.json",
            headers = headers(),
            json = payload
        )

        if (response.text.isBlank()) {
            throw ErrorLoadingException("HUHU katalog cevabı boş")
        }

        return parseCatalog(response.text)
    }

    /*
     * Gerçek HUHU response şeması:
     *
     * {
     *   "nextCursor": ...,
     *   "items": [{
     *      "type":"iptv",
     *      "ids":{"id":"..."},
     *      "url":"https://huhu.to/huhu-iptv/play/...",
     *      "name":"NTV |E",
     *      "group":"Turkey",
     *      "logo":"..."
     *   }]
     * }
     *
     * HUHU Kanal 1/2/3 gibi yapay isim YOK.
     */
    private fun parseCatalog(raw: String): CatalogPage {
        val root = mapper.readTree(raw)

        val nextCursorNode = root.get("nextCursor")
        val nextCursor =
            if (nextCursorNode == null || nextCursorNode.isNull) null
            else nextCursorNode.asInt()

        val itemsNode = root.get("items")
        if (itemsNode == null || !itemsNode.isArray) {
            return CatalogPage(emptyList(), nextCursor)
        }

        val channels = ArrayList<Channel>()

        for (item in itemsNode) {
            if (item.get("type")?.asText() != "iptv") continue
            if (item.get("group")?.asText() != "Turkey") continue

            val id = item.get("ids")
                ?.get("id")
                ?.asText()
                ?.trim()
                .orEmpty()

            val channelName = item.get("name")
                ?.asText()
                ?.trim()
                .orEmpty()

            val playUrl = item.get("url")
                ?.asText()
                ?.trim()
                .orEmpty()

            val logo = item.get("logo")
                ?.asText()
                ?.trim()
                ?.takeIf { it.startsWith("http") }

            if (id.isBlank() || channelName.isBlank()) continue

            channels += Channel(
                id = id,
                name = channelName,
                url = playUrl.ifBlank {
                    "$mainUrl/huhu-iptv/play/$id"
                },
                logo = logo
            )
        }

        return CatalogPage(
            items = channels.distinctBy { it.id },
            nextCursor = nextCursor
        )
    }

    private fun extractId(value: String): String? {
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

    private fun findM3u8(raw: String): String? {
        val root: JsonNode? = runCatching {
            mapper.readTree(raw)
        }.getOrNull()

        if (root != null) {
            var result: String? = null

            fun walk(node: JsonNode) {
                if (result != null) return

                when {
                    node.isTextual -> {
                        val value = node.asText().trim()
                        if (
                            value.startsWith("http") &&
                            value.contains(".m3u8", ignoreCase = true)
                        ) {
                            result = value
                        }
                    }

                    node.isArray -> node.forEach(::walk)

                    node.isObject -> {
                        val fields = node.fields()
                        while (fields.hasNext() && result == null) {
                            walk(fields.next().value)
                        }
                    }
                }
            }

            walk(root)
            if (result != null) return result
        }

        val clean = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        return Regex(
            """https?://[^\s"'\\]+\.m3u8(?:\?[^\s"'\\]*)?"""
        ).find(clean)?.value
    }

    companion object {
        private const val MAX_CATALOG_PAGES = 20

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"
    }
}
