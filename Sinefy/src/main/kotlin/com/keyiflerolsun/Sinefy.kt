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

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/page/1" to "Filmler",
        "$mainUrl/dizi-izle/netflix/1" to "Netflix Dizileri",
        "$mainUrl/seri-filmler/page/1" to "Seri Filmler",
        "$mainUrl/gozat/filmler/aksiyon" to "Aksiyon",
        "$mainUrl/gozat/filmler/macera" to "Macera",
        "$mainUrl/gozat/filmler/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/gozat/filmler/dram" to "Dram",
        "$mainUrl/gozat/filmler/komedi" to "Komedi",
        "$mainUrl/gozat/filmler/korku" to "Korku",
        "$mainUrl/gozat/filmler/gerilim" to "Gerilim",
        "$mainUrl/gozat/filmler/gizem" to "Gizem",
        "$mainUrl/gozat/filmler/fantastik" to "Fantastik",
        "$mainUrl/gozat/filmler/animasyon" to "Animasyon",
        "$mainUrl/gozat/filmler/aile" to "Aile",
        "$mainUrl/gozat/filmler/romantik" to "Romantik",
        "$mainUrl/gozat/filmler/savas" to "Savaş",
        "$mainUrl/gozat/filmler/suc" to "Suç",
        "$mainUrl/gozat/filmler/western" to "Western"
    )

    private fun cleanUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().replace("\\/", "/").replace("\\_", "_")
        val md = Regex("\\[([^]]+)]\\((https?://[^)]+)\\)").find(value)
        if (md != null) value = md.groupValues[2]
        return fixUrlNull(value)
    }

    private fun imageFrom(img: Element?): String? {
        if (img == null) return null
        val srcset = img.attr("data-srcset").trim()
        if (srcset.isNotBlank()) {
            val candidates = srcset.split(',').mapNotNull { part ->
                val url = part.trim().substringBefore(' ').trim()
                cleanUrl(url)
            }
            if (candidates.isNotEmpty()) return candidates.last()
        }
        return sequenceOf("data-src", "data-lazy-src", "data-original", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
            ?.let(::cleanUrl)
    }

    private fun posterOf(card: Element): String? = imageFrom(card.selectFirst(".poster-media img"))

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+izle$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun catalogCards(document: Document, collections: Boolean = false): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val cards = if (collections) {
            document.select("li.segment-poster")
        } else {
            document.select("li.segment-poster.movie")
        }

        cards.forEach { card ->
            val link = card.selectFirst(".poster-media a[href]") ?: return@forEach
            val href = cleanUrl(link.attr("href")) ?: return@forEach
            if (collections) {
                if (!href.startsWith("$mainUrl/seri-filmler/")) return@forEach
            } else {
                if (!href.startsWith("$mainUrl/izle/")) return@forEach
                if (Regex("/sezon-\\d+/bolum-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@forEach
            }

            val title = sequenceOf(
                card.selectFirst(".poster-subject h2.truncate")?.text(),
                link.attr("title"),
                link.selectFirst("img")?.attr("alt")
            ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: return@forEach

            val poster = posterOf(card)
            val item = if (collections) {
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            } else {
                // /izle/... hem film hem dizi için kullanılıyor. CloudStream detayda gerçek tipi belirleyecek.
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            }
            out[href] = item
        }
        return out.values.toList()
    }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return when {
            Regex("/page/\\d+/?$").containsMatchIn(base) -> base.replace(Regex("/page/\\d+/?$"), "/page/$page")
            Regex("/dizi-izle/[^/]+/\\d+/?$").containsMatchIn(base) -> base.replace(Regex("/\\d+/?$"), "/$page")
            Regex("/gozat/.+/\\d+/?$").containsMatchIn(base) -> base.replace(Regex("/\\d+/?$"), "/$page")
            base.contains("/gozat/") -> "${base.trimEnd('/')}/$page"
            else -> base
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val document = app.get(url).document
        val collections = request.data.contains("/seri-filmler/")
        return newHomePageResponse(request.name, catalogCards(document, collections))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        listOf("$mainUrl/page/1", "$mainUrl/dizi-izle/netflix/1", "$mainUrl/seri-filmler/page/1").forEach { url ->
            runCatching { app.get(url).document }.getOrNull()?.let { doc ->
                catalogCards(doc, url.contains("/seri-filmler/"))
                    .filter { it.name.lowercase().contains(needle) }
                    .forEach { out[it.url] = it }
            }
        }
        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun profilePoster(document: Document): String? =
        imageFrom(document.selectFirst("#series-profile-image-wrapper img.series-profile-thumb"))
            ?: imageFrom(document.selectFirst("img.series-profile-thumb"))

    private fun ownEpisodes(document: Document, canonicalUrl: String): List<Episode> {
        val root = canonicalUrl.substringBefore("/sezon-").trimEnd('/')
        val out = LinkedHashMap<String, Episode>()
        document.select(".episodes-list div.item").forEach { item ->
            val link = item.selectFirst("td.table-episodes-title a[href], .ordilabel a[href]") ?: return@forEach
            val href = cleanUrl(link.attr("href")) ?: return@forEach
            if (!href.startsWith("$root/sezon-")) return@forEach
            val m = Regex("/sezon-(\\d+)/bolum-(\\d+)", RegexOption.IGNORE_CASE).find(href) ?: return@forEach
            val season = m.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = m.groupValues[2].toIntOrNull() ?: return@forEach
            out[href] = newEpisode(href) {
                name = link.attr("title").trim().takeIf { it.isNotBlank() } ?: "$episode. Bölüm"
                this.season = season
                this.episode = episode
            }
        }
        return out.values.toList()
    }

    private fun collectionMovies(document: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        document.select("li.segment-poster-h").forEach { card ->
            val link = card.selectFirst("a[href]") ?: return@forEach
            val href = cleanUrl(link.attr("href")) ?: return@forEach
            if (!href.startsWith("$mainUrl/izle/")) return@forEach
            val title = link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.selectFirst("h2,h3,h4")?.text()?.trim()
                ?: return@forEach
            val poster = imageFrom(card.selectFirst("img"))
            out[href] = newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) { posterUrl = poster }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val canonical = cleanUrl(document.selectFirst("link[rel=canonical]")?.attr("href")) ?: url.substringBefore('?')

        if (canonical.contains("/seri-filmler/")) {
            val title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null
            val poster = imageFrom(document.selectFirst("img[alt=${title.replace(" film serisi", "")}]") )
                ?: document.selectFirst("script[type=application/ld+json]")?.data()
                    ?.let { Regex("\\\"image\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(it)?.groupValues?.getOrNull(1) }
                    ?.let(::cleanUrl)
            val movies = collectionMovies(document)
            return newMovieLoadResponse(cleanTitle(title), canonical, TvType.Movie, canonical) {
                posterUrl = poster
                plot = document.selectFirst("meta[name=description]")?.attr("content")
                recommendations = movies
            }
        }

        val title = sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: return null

        val poster = profilePoster(document)
        val plot = sequenceOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content")
        ).mapNotNull { it?.trim() }.firstOrNull { it.isNotBlank() }

        val episodes = ownEpisodes(document, canonical)
        val currentEpisode = Regex("/sezon-(\\d+)/bolum-(\\d+)", RegexOption.IGNORE_CASE).find(canonical)
        val isSeries = episodes.isNotEmpty() || currentEpisode != null || document.select("script[type=application/ld+json]").any { it.data().contains("\\\"@type\\\":\\\"TVSeries\\\"") }

        return if (isSeries) {
            val finalEpisodes = if (episodes.isNotEmpty()) episodes else if (currentEpisode != null) {
                val season = currentEpisode.groupValues[1].toIntOrNull()
                val episode = currentEpisode.groupValues[2].toIntOrNull()
                listOf(newEpisode(canonical) { this.season = season; this.episode = episode })
            } else emptyList()
            newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, finalEpisodes) {
                posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                posterUrl = poster
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
        Log.d("SINEFY", "PARS_BROWSER_HANDOFF detail=$data")
        callback.invoke(
            newExtractorLink(
                source = "PARS V53 Browser",
                name = "PARS V53 Browser",
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                referer = data
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to data,
                    "X-PARS-WEBVIEW" to "1",
                    "X-PARS-DETAIL-REFERER" to data
                )
                quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
