package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FullHDFilmizle : MainAPI() {
    override var mainUrl = "https://fullhdfilmizle.now"
    override var name = "FullHDFilmizle"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/" to "Filmler",
        "/yabanci-dizi-izle" to "Diziler",
        "/tur/aksiyon" to "Aksiyon",
        "/tur/dram" to "Dram",
        "/tur/komedi" to "Komedi",
        "/tur/korku" to "Korku",
        "/tur/macera" to "Macera",
        "/tur/bilim-kurgu" to "Bilim Kurgu"
    )

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = selectFirst("a.mc-link")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".film-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a.mc-link")?.attr("title")?.removeSuffix(" izle")?.trim()
            ?: return null
        val poster = selectFirst("img.mc-afis")?.let { img ->
            listOf("data-src", "data-original", "src").firstNotNullOfOrNull { key ->
                img.attr(key).takeIf { it.isNotBlank() }
            }
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster?.let(::fixUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (page <= 1) fixUrl(path) else {
            val sep = if (path.contains("?")) "&" else "?"
            fixUrl("$path${sep}page=$page")
        }

        val doc = app.get(url, headers = headers()).document
        val items = doc.select("article.movie-card").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/arama?q=${query.replace(" ", "+")}",
            headers = headers()
        ).document
        return doc.select("article.movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers()).document

        val title = doc.selectFirst(".film-title-h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("""\s+Full HD.*$"""), "")
                ?.trim()
            ?: throw ErrorLoadingException("Başlık bulunamadı")

        val poster = doc.selectFirst(".detail-poster img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val backdrop = doc.selectFirst("""link[rel=preload][as=image]""")?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val overview = doc.selectFirst(".detail-synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("""\((\d{4})\)""")
            .find(doc.title())?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let(::fixUrl)
            this.backgroundPosterUrl = backdrop?.let(::fixUrl)
            this.plot = overview
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FHD_DIAG LOAD_LINKS detail=$data")

        val detail = app.get(data, headers = headers())
        println("FHD_DIAG DETAIL status=${detail.code} final=${detail.url}")

        val doc = detail.document
        val frames = doc.select("iframe[src]").map { fixUrl(it.attr("src")) }.distinct()
        println("FHD_DIAG IFRAMES count=${frames.size}")

        frames.forEachIndexed { index, frame ->
            println("FHD_DIAG IFRAME[$index]=$frame")
        }

        val vidMixi = frames.firstOrNull {
            runCatching { java.net.URI(it).host?.contains("vidmixi.com", ignoreCase = true) == true }
                .getOrDefault(false)
        }

        if (vidMixi == null) {
            println("FHD_DIAG FAIL stage=DETAIL reason=VIDMIXI_IFRAME_NOT_FOUND")
            return false
        }

        println("FHD_DIAG VIDMIXI_HANDOFF url=$vidMixi")
        return loadExtractor(
            url = vidMixi,
            referer = data,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}
