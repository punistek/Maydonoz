package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Sinefy : MainAPI() {
    override var mainUrl = "https://sinefy3.com"
    override var name = "Sinefy"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Filmler",
        "${mainUrl}/dizi-izle" to "Diziler",
        "${mainUrl}/seri-filmler" to "Seri Filmler",
        "${mainUrl}/gozat/filmler/bilim-kurgu" to "Bilim Kurgu"
    )

    private fun imageOf(element: Element): String? {
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        val raw = sequenceOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("src")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
        return fixUrlNull(raw)
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+izle$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        // Kullanıcının verdiği gerçek Sinefy URL aileleri dışında menü/reklam linklerini alma.
        val isWatch = href.startsWith("${mainUrl}/izle/")
        val isSeriesPage = href.startsWith("${mainUrl}/dizi/") || href.startsWith("${mainUrl}/dizi-izle/")
        val isCollection = href.startsWith("${mainUrl}/seri-filmler/")
        if (!isWatch && !isSeriesPage && !isCollection) return null

        // Bölüm URL'leri ana sayfa kartı olarak gösterilmesin.
        if (Regex("/sezon-\\d+/bolum-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)) return null

        val title = sequenceOf(
            link.attr("title"),
            link.selectFirst("img")?.attr("alt"),
            selectFirst("h2,h3,h4,h5")?.text(),
            link.text()
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: return null

        val poster = imageOf(this)
        val series = isSeriesPage || isCollection
        return if (series) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun collectCards(document: Document): List<SearchResponse> {
        val result = LinkedHashMap<String, SearchResponse>()
        // Class adı tahmin etmiyoruz; yalnız Sinefy'nin kanıtlı URL ailelerine bağlı anchorları tarıyoruz.
        document.select("a[href]").forEach { anchor ->
            anchor.toSearchResult()?.let { result[it.url] = it }
        }
        return result.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sinefy pagination biçimi kanıtlanmadığı için sahte ?page= üretmiyoruz.
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val document = app.get(request.data).document
        return newHomePageResponse(request.name, collectCards(document))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Sinefy arama endpoint'i elimizde kanıtlı değil. URL uydurmak yerine bilinen katalogları tarıyoruz.
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()

        val result = LinkedHashMap<String, SearchResponse>()
        listOf("${mainUrl}/", "${mainUrl}/dizi-izle", "${mainUrl}/seri-filmler").forEach { url ->
            runCatching { app.get(url).document }.getOrNull()?.let { document ->
                collectCards(document)
                    .filter { it.name.lowercase().contains(needle) }
                    .forEach { result[it.url] = it }
            }
        }
        return result.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: return null

        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img")?.let(::imageOf)
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }?.let { fixUrlNull(it) }

        val plot = sequenceOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content")
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }

        val episodes = LinkedHashMap<String, Episode>()
        document.select("a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            val match = Regex("/sezon-(\\d+)/bolum-(\\d+)", RegexOption.IGNORE_CASE).find(href) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
            episodes[href] = newEpisode(href) {
                this.name = anchor.text().trim().takeIf { it.isNotBlank() } ?: "$season. Sezon $episode. Bölüm"
                this.season = season
                this.episode = episode
            }
        }

        val currentEpisode = Regex("/sezon-(\\d+)/bolum-(\\d+)", RegexOption.IGNORE_CASE).find(url)
        if (currentEpisode != null && episodes.isEmpty()) {
            val season = currentEpisode.groupValues[1].toIntOrNull()
            val episode = currentEpisode.groupValues[2].toIntOrNull()
            episodes[url] = newEpisode(url) {
                this.season = season
                this.episode = episode
            }
        }

        val isSeries = episodes.isNotEmpty() || currentEpisode != null || url.contains("/dizi/", true)
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        /*
         * Resolver Lab'da Sinefy için kanıtlanan çözüm browser runtime/session gerektiriyor.
         * FullHDFilmizlesene'deki çalışan PARS handoff sözleşmesini aynen kullanıyoruz.
         * CloudStream içinde source2.php/m.php URL tahmini veya statik Pichive çözümü yok.
         */
        Log.d("SINEFY", "PARS_BROWSER_HANDOFF detail=$data")

        callback.invoke(
            newExtractorLink(
                source = "PARS V53 Browser",
                name = "PARS V53 Browser",
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to data,
                    "X-PARS-WEBVIEW" to "1",
                    "X-PARS-DETAIL-REFERER" to data
                )
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
