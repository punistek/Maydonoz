package com.lagradost.cloudstream3.plugins.tizam

// PARS_TIZAM_FIX_V2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Tizam : MainAPI() {
    override var mainUrl = "https://ane.tizam.org"
    override var name = "Tizam"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/fil_my_dlya_vzroslyh/novinki/" to "Yeni Filmler",
        "$mainUrl/podborki/porno_hd/" to "HD",
        "$mainUrl/fil_my_dlya_vzroslyh/s_russkim_perevodom/" to "Rusça Çeviri",
        "$mainUrl/fil_my_dlya_vzroslyh/polnometrazhnye/" to "Uzun Metraj",
        "$mainUrl/fil_my_dlya_vzroslyh/klassika/" to "Klasik / Retro",
        "$mainUrl/korotkometrazhnye/" to "Kısa Videolar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val document = app.get(url, headers = headers).document

        val items = document.select(
            "div.item, article.item, li.item, div[class*=item]"
        ).mapNotNull(::toSearchResult)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty()
        )
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        // Site sayfalama biçimi değişirse yalnızca bu fonksiyon güncellenir.
        return if (base.contains("?")) "$base&page=$page" else "${base}?p=$page"
    }

    private fun toSearchResult(root: Element): SearchResponse? {
        val link = root.selectFirst(
            "a.item__cover[href], h3.item__title a[href], a[itemprop=url][href]"
        ) ?: return null

        val href = link.absUrl("href").ifBlank {
            absoluteUrl(link.attr("href"))
        }

        if (href.isBlank() || !isContentUrl(href)) return null

        val title = root.selectFirst(
            ".item__title .title, .item__title, [itemprop=name]"
        )?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: root.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null

        val poster = root.selectFirst(
            "img.item__img, img[itemprop=thumbnail], img"
        )?.let { img ->
            img.absUrl("src").ifBlank {
                absoluteUrl(img.attr("src"))
            }
        }?.takeIf { it.isNotBlank() }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun isContentUrl(url: String): Boolean {
        return url.contains("/fil_my_dlya_vzroslyh/") ||
            url.contains("/korotkometrazhnye/") ||
            url.contains("/podborki/")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search-results/?search_string=$encoded"
        val document = app.get(url, headers = headers).document

        return document.select(
            "div.item, article.item, li.item, div[class*=item]"
        ).mapNotNull(::toSearchResult)
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").trim()

        val poster = document.selectFirst("video#player_1[poster], video[poster]")
            ?.attr("poster")
            ?.let(::absoluteUrl)
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.let(::absoluteUrl)
                ?.takeIf { it.isNotBlank() }

        val description = document.selectFirst(
            ".film__text, .film__collapse, meta[name=description]"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()?.takeIf { it.isNotBlank() }

        val durationText = document.selectFirst(
            "[umi\\:field-name=prodolzhitelnost], [itemprop=duration]"
        )?.text()?.trim()

        val durationMinutes = parseDurationMinutes(durationText)

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            url
        ) {
            posterUrl = poster
            plot = description
            duration = durationMinutes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = headers + mapOf("Referer" to "$mainUrl/")
        ).document

        val sources = document.select(
            "video#player_1 source[src], video source[src]"
        )

        var found = false

        sources.forEach { source ->
            val raw = source.attr("src").trim()
            if (raw.isBlank()) return@forEach

            val videoUrl = absoluteUrl(raw)
            if (!videoUrl.startsWith("http")) return@forEach

            val type = source.attr("type").lowercase()
            val resText = source.attr("data-res").trim()
            val quality = resText.toIntOrNull() ?: 0

            val linkType = when {
                type.contains("mpegurl") ||
                    videoUrl.contains(".m3u8", ignoreCase = true) ->
                    ExtractorLinkType.M3U8

                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = if (resText.isNotBlank()) "$name ${resText}p" else name,
                    url = videoUrl,
                    type = linkType
                ) {
                    this.referer = data
                    this.quality = quality
                    this.headers = headers + mapOf("Referer" to data)
                }
            )
            found = true
        }

        return found
    }

    private fun parseDurationMinutes(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0]
            3 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    private fun absoluteUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return if (url.startsWith("//")) {
            "https:$url"
        } else {
            "$mainUrl/${url.trimStart('/')}"
        }
    }
}
