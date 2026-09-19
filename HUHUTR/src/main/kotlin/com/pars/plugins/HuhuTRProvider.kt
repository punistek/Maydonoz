package com.pars.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class HuhuTRProvider(
    private val snapshotJson: String? = null,
) : MainAPI() {

    override var mainUrl = "https://huhu.to"
    override var name = "HUHU Türkiye"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val mapper = ObjectMapper()

    private data class Channel(
        val id: String,
        val url: String,
        val name: String,
        val logo: String?,
    )

    private data class CatalogPage(
        val items: List<Channel>,
        val nextCursor: Int?,
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
        "User-Agent" to USER_AGENT,
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val catalog = runCatching { fetchCatalogPage(page) }
            .getOrElse {
                // Ağ/katalog geçici bozulursa ZIP içindeki gerçek 300 kanallık
                // 19.09.2026 Turkey response'u ilk sayfa için yedek olarak kullanılır.
                if (page == 1) parseCatalog(snapshotJson.orEmpty())
                else CatalogPage(emptyList(), null)
            }

        val results = catalog.items.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch?live=${channel.id}",
                TvType.Live,
                fix = false,
            ) {
                posterUrl = channel.logo
            }
        }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = catalog.nextCursor != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val page = runCatching { requestCatalog(cursor = null, search = q) }
            .getOrElse {
                val local = parseCatalog(snapshotJson.orEmpty()).items
                    .filter { it.name.contains(q, ignoreCase = true) }
                CatalogPage(local, null)
            }

        return page.items.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/watch?live=${channel.id}",
                TvType.Live,
                fix = false,
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

        val cached = parseCatalog(snapshotJson.orEmpty()).items
            .firstOrNull { it.id == id }

        val title = cached?.name ?: "HUHU Canlı TV"

        return newLiveStreamLoadResponse(
            title,
            "$mainUrl/watch?live=$id",
            id,
        ) {
            posterUrl = cached?.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = extractId(data)
            ?: throw ErrorLoadingException("HUHU kanal ID bulunamadı")

        val watchUrl = "$mainUrl/watch?live=$id"

        // DevTools'tan alınan gerçek HUHU resolver payload'u.
        val payload = mapOf(
            "language" to "de",
            "region" to "DE",
            "url" to "$mainUrl/huhu-iptv/play/$id",
        )

        val response = app.post(
            "$mainUrl/mediaurl-resolve.json",
            headers = headers(watchUrl),
            json = payload,
        )

        val streamUrl = findStreamUrl(response.text)
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

    /**
     * HUHU response ilk sayfada nextCursor=300 döndürüyor.
     * CloudStream page=1,2,3... istediği için cursor zincirini HUHU'nun
     * kendi nextCursor değeriyle takip ediyoruz; 300 varsayımı yapmıyoruz.
     */
    private suspend fun fetchCatalogPage(page: Int): CatalogPage {
        if (page <= 1) return requestCatalog(cursor = null, search = "")

        var cursor: Int? = null
        var current = 1

        while (current < page) {
            val previous = requestCatalog(cursor = cursor, search = "")
            cursor = previous.nextCursor ?: return CatalogPage(emptyList(), null)
            current++
        }

        return requestCatalog(cursor = cursor, search = "")
    }

    private suspend fun requestCatalog(
        cursor: Int?,
        search: String,
    ): CatalogPage {
        val payload = mapOf(
            "language" to "de",
            "region" to "DE",
            "catalogId" to "iptv",
            "id" to "",
            "adult" to false,
            "search" to search,
            "sort" to "trending-region",
            "filter" to mapOf("group" to "Turkey"),
            "cursor" to cursor,
        )

        val response = app.post(
            "$mainUrl/mediaurl-catalog.json",
            headers = headers(),
            json = payload,
        )

        val parsed = parseCatalog(response.text)
        if (parsed.items.isEmpty() && response.text.isBlank()) {
            throw ErrorLoadingException("HUHU katalog cevabı boş")
        }
        return parsed
    }

    /**
     * Artık tahmini alan araması yok.
     * Kullanıcının verdiği gerçek response şeması:
     * items[].ids.id, items[].url, items[].name, items[].group, items[].logo
     * ve root.nextCursor.
     */
    private fun parseCatalog(raw: String): CatalogPage {
        if (raw.isBlank()) return CatalogPage(emptyList(), null)

        val root = mapper.readTree(raw)
        val nextCursor = root.get("nextCursor")
            ?.takeUnless { it.isNull }
            ?.asInt()

        val itemsNode = root.get("items")
        if (itemsNode == null || !itemsNode.isArray) {
            return CatalogPage(emptyList(), nextCursor)
        }

        val channels = itemsNode.mapNotNull { item ->
            if (item.get("group")?.asText() != "Turkey") return@mapNotNull null
            if (item.get("type")?.asText() != "iptv") return@mapNotNull null

            val id = item.get("ids")?.get("id")?.asText()?.trim().orEmpty()
            val channelName = item.get("name")?.asText()?.trim().orEmpty()
            val playUrl = item.get("url")?.asText()?.trim().orEmpty()
            val logo = item.get("logo")?.asText()?.trim()
                ?.takeIf { it.startsWith("http") }

            if (id.isBlank() || channelName.isBlank()) return@mapNotNull null

            Channel(
                id = id,
                url = playUrl.ifBlank { "$mainUrl/huhu-iptv/play/$id" },
                name = channelName,
                logo = logo,
            )
        }

        return CatalogPage(
            items = channels.distinctBy { it.id },
            nextCursor = nextCursor,
        )
    }

    private fun extractId(value: String): String? {
        Regex("""[?&]live=([A-Za-z0-9_-]+)""")
            .find(value)?.groupValues?.getOrNull(1)?.let { return it }

        Regex("""huhu-iptv/play/([A-Za-z0-9_-]+)""")
            .find(value)?.groupValues?.getOrNull(1)?.let { return it }

        return value.trim().takeIf {
            it.length >= 8 && it.matches(Regex("""[A-Za-z0-9_-]+"""))
        }
    }

    /**
     * Resolver response şemasını kullanıcı henüz ayrı Response olarak
     * paylaşmadığı için alan adı uydurmuyoruz. JSON içindeki bütün string
     * değerlerden gerçek .m3u8 URL'sini seçiyoruz.
     */
    private fun findStreamUrl(raw: String): String? {
        val root: JsonNode = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return regexM3u8(raw)

        var result: String? = null

        fun walk(node: JsonNode) {
            if (result != null) return

            when {
                node.isTextual -> {
                    val value = node.asText().trim()
                    if (value.startsWith("http") &&
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
        return result ?: regexM3u8(raw)
    }

    private fun regexM3u8(raw: String): String? {
        val clean = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        return Regex("""https?://[^\s"'\\]+\.m3u8(?:\?[^\s"'\\]*)?""")
            .find(clean)
            ?.value
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"
    }
}
