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
    override val hasQuickSearch = false
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/seri-filmler" to "Seri Filmler",
        "$mainUrl/gozat/filmler/bilim-kurgu" to "Bilim Kurgu"
    )

    private val pageHeaders get() = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Kullanıcının verdiği gerçek sayfalar baz alınır. Bilinmeyen pagination endpoint'i üretilmez.
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data, headers = pageHeaders, referer = "$mainUrl/").document
        val items = parseCards(doc)
        Log.d("PARS_SINEFY", "HOME name=${request.name} items=${items.size} url=${request.data}")
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        // Class isimlerine kilitlenmek yerine Sinefy'nin içerik URL sözleşmesini kullanır.
        val anchors = doc.select(
            "a[href*=/izle/], a[href*=/seri-filmler/], a[href*=/dizi/]"
        )
        return anchors.mapNotNull(::anchorToSearch).distinctBy { it.url }
    }

    private fun anchorToSearch(a: Element): SearchResponse? {
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (href == "$mainUrl/" || href.endsWith("/dizi-izle") || href.endsWith("/seri-filmler")) return null
        if (Regex("/izle/[^/]+/sezon-\\d+/bolum-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)) return null

        val img = a.selectFirst("img")
        val title = listOf(
            a.attr("title"),
            img?.attr("alt").orEmpty(),
            a.selectFirst("[class*=title], [class*=name]")?.text().orEmpty(),
            a.text()
        ).firstOrNull { it.isNotBlank() }?.trim() ?: return null

        val poster = img?.let {
            sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { key -> it.attr(key) }
                .firstOrNull { value -> value.isNotBlank() }
        }?.let(::fixUrlNull)

        val series = href.contains("/dizi/") || href.contains("/sezon-") || href.contains("/bolum-")
        return if (series) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Resolver kayıtlarında Sinefy arama endpoint'i kanıtlanmadı. Endpoint uydurmak yerine
        // gerçek katalog sayfalarında güvenli yerel arama yapıyoruz.
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        val pages = listOf(
            "$mainUrl/",
            "$mainUrl/dizi-izle",
            "$mainUrl/seri-filmler",
            "$mainUrl/gozat/filmler/bilim-kurgu"
        )
        val out = LinkedHashMap<String, SearchResponse>()
        pages.forEach { url ->
            runCatching {
                parseCards(app.get(url, headers = pageHeaders, referer = "$mainUrl/").document)
            }.getOrDefault(emptyList()).forEach { item ->
                if (item.name.contains(needle, ignoreCase = true)) out[item.url] = item
            }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = pageHeaders, referer = "$mainUrl/").document
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" izle", missingDelimiterValue = doc.title())
            ?.substringBefore(" |")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" izle").substringBefore(" |").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlNull)
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("(?:19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        // Dizi watch sayfasında resolver'ın gördüğü gerçek bölüm URL biçimi:
        // /izle/<slug>/sezon-N/bolum-N
        val episodeAnchors = doc.select("a[href*=/izle/][href*=/sezon-][href*=/bolum-]")
        val episodes = episodeAnchors.mapNotNull { a ->
            val epUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m = Regex("/sezon-(\\d+)/bolum-(\\d+)", RegexOption.IGNORE_CASE).find(epUrl) ?: return@mapNotNull null
            val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val episode = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            newEpisode(epUrl) {
                this.season = season
                this.episode = episode
                this.name = a.attr("title").ifBlank { a.text().trim() }.ifBlank { "$season. Sezon $episode. Bölüm" }
            }
        }.distinctBy { it.data }.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        val isSeries = episodes.isNotEmpty() || Regex("/sezon-\\d+/bolum-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(url)
        if (isSeries) {
            // Bölüm URL'sinden açıldıysa aynı sayfadaki sezon/bölüm linkleri dizi load listesine dönüşür.
            val safeEpisodes = if (episodes.isNotEmpty()) episodes else listOf(newEpisode(url) {
                val m = Regex("/sezon-(\\d+)/bolum-(\\d+)", RegexOption.IGNORE_CASE).find(url)
                season = m?.groupValues?.getOrNull(1)?.toIntOrNull()
                episode = m?.groupValues?.getOrNull(2)?.toIntOrNull()
                name = "Bölüm"
            })
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, safeEpisodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        /*
         * V53 ile kanıtlanan Sinefy akışı statik HTTP extractor değildir:
         * detail/episode -> pichive iframe -> gerçek browser/runtime -> Play/session ->
         * runtime JSON source -> dinamik HLS -> manifest/media proof.
         *
         * Bu nedenle source2.php tokenı, cookie veya final HLS burada tahmin edilmez.
         * Mevcut PARS browser resolver mekanizmasına gerçek içerik URL'si teslim edilir.
         * FullHDFilmizlesene modülündeki çalışan handoff sözleşmesi korunmuştur.
         */
        Log.d("PARS_SINEFY", "V53_BROWSER_HANDOFF detail=$data")

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
