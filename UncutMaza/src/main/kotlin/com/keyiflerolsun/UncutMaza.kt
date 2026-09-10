package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class UncutMaza : MainAPI() {
    override var mainUrl = "https://uncutmaza.cc"
    override var name = "UncutMaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; SM-A346E) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    private val htmlHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/?filter=latest" to "Latest",
        "$mainUrl/?filter=most-viewed" to "Most Viewed",
        "$mainUrl/?filter=longest" to "Longest",
        "$mainUrl/?filter=popular" to "Popular",
        "$mainUrl/?filter=random" to "Random"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base

        val filter = runCatching {
            java.net.URI(base).query
                ?.split("&")
                ?.firstOrNull { it.startsWith("filter=") }
                ?.substringAfter("filter=")
        }.getOrNull()

        return if (!filter.isNullOrBlank()) {
            "$mainUrl/page/$page/?filter=$filter"
        } else {
            "$mainUrl/page/$page/"
        }
    }

    private fun cardToSearch(card: Element): SearchResponse? {
        val anchor = card.selectFirst("a[href]") ?: return null
        val hrefRaw = anchor.attr("href").trim()
        if (hrefRaw.isBlank()) return null

        val href = fixUrlNull(hrefRaw) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val title = anchor.attr("title")
            .ifBlank { card.selectFirst("header.entry-header span")?.text().orEmpty() }
            .ifBlank { card.selectFirst("img.video-main-thumb")?.attr("alt").orEmpty() }
            .trim()

        if (title.isBlank()) return null

        val img = card.selectFirst("img.video-main-thumb")

        val posterRaw =
            card.attr("data-main-thumb").takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf {
                    it.isNotBlank() && !it.startsWith("data:", true)
                }

        val poster = posterRaw?.let { fixUrlNull(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val selectors = listOf(
            "#main .videos-list article.loop-video.thumb-block",
            "#main .videos-list article.thumb-block",
            ".videos-list article.thumb-block",
            "article.loop-video.thumb-block",
            "article.thumb-block.video-preview-item",
            "article.thumb-block"
        )

        val seenNodes = linkedSetOf<Element>()
        selectors.forEach { selector ->
            document.select(selector).forEach { seenNodes.add(it) }
        }

        return seenNodes
            .mapNotNull(::cardToSearch)
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)

        Log.d("UNCUTMAZA", "MAIN_START category=${request.name} page=$page url=$url")

        val response = app.get(
            url,
            headers = htmlHeaders,
            referer = "$mainUrl/"
        )

        val document = response.document
        val items = parseCards(document)

        Log.d(
            "UNCUTMAZA",
            "MAIN_DONE category=${request.name} http=${response.code} " +
                "articles=${document.select("article.thumb-block").size} " +
                "videosList=${document.select(".videos-list").size} " +
                "items=${items.size} title=${document.title()}"
        )

        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"

        val response = app.get(
            url,
            headers = htmlHeaders,
            referer = "$mainUrl/"
        )

        val items = parseCards(response.document)

        Log.d(
            "UNCUTMAZA",
            "SEARCH query=$query http=${response.code} items=${items.size}"
        )

        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            url,
            headers = htmlHeaders,
            referer = "$mainUrl/"
        )

        val document = response.document

        val title =
            document.selectFirst("meta[itemprop=name]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("h1.entry-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
        )

        val plot =
            document.selectFirst("meta[itemprop=description]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:description]")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[name=description]")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }

        val tags = document.select("a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            headers = htmlHeaders,
            referer = "$mainUrl/"
        )

        val document = response.document
        val candidates = linkedSetOf<String>()

        document.select("meta[itemprop=contentUrl]").forEach { node ->
            node.attr("content")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(candidates::add)
        }

        document.select("video source[src], video[src], source[src]").forEach { node ->
            val raw = node.attr("src").trim()
            if (raw.isNotBlank()) candidates.add(raw)
        }

        // Clean Tube Player bazen kaynak URL'sini iframe parametresine gömer.
        document.select("iframe[data-lazy-src], iframe[src]").forEach { iframe ->
            val raw = iframe.attr("data-lazy-src")
                .ifBlank { iframe.attr("src") }

            if (raw.contains("player-x.php", true)) {
                runCatching {
                    val q = java.net.URI(raw).rawQuery
                        ?.split("&")
                        ?.firstOrNull { it.startsWith("q=") }
                        ?.substringAfter("q=")
                    if (!q.isNullOrBlank()) {
                        val decoded = String(
                            android.util.Base64.decode(
                                java.net.URLDecoder.decode(q, "UTF-8"),
                                android.util.Base64.DEFAULT
                            )
                        )
                        Regex(
                            """https?://[^"'\\\s<>]+?\.mp4(?:\?[^"'\\\s<>]*)?""",
                            RegexOption.IGNORE_CASE
                        ).findAll(decoded).forEach { candidates.add(it.value) }
                    }
                }
            }
        }

        val links = candidates
            .mapNotNull { fixUrlNull(it) }
            .filter {
                it.startsWith("http", true) &&
                    (
                        it.contains(".mp4", true) ||
                        it.contains(".m3u8", true)
                    )
            }
            .distinct()

        Log.d("UNCUTMAZA", "LINKS page=$data candidates=${links.size}")

        if (links.isEmpty()) return false

        links.forEachIndexed { index, videoUrl ->
            val isHls = videoUrl.contains(".m3u8", true)

            callback(
                newExtractorLink(
                    source = name,
                    name = if (links.size == 1) name else "$name ${index + 1}",
                    url = videoUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to ua,
                        "Accept" to "*/*",
                        "Range" to "bytes=0-"
                    )
                }
            )
        }

        return true
    }
}
