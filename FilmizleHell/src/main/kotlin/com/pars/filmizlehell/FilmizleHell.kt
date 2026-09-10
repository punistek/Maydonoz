package com.pars.filmizlehell

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmizleHell : MainAPI() {

    override var mainUrl = "https://filmizlehell.com"
    override var name = "FilmizleHell"
    override val hasMainPage = true
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
    "$mainUrl/filmler" to "Filmler",

    "$mainUrl/tur/1-aksiyon-002" to "Aksiyon",
    "$mainUrl/tur/7-macera-001" to "Macera",
    "$mainUrl/tur/8-bilim-kurgu-001" to "Bilim Kurgu",
    "$mainUrl/tur/9-savas-001" to "Savaş",
    "$mainUrl/tur/10-dram-001" to "Dram",
    "$mainUrl/tur/11-yerli-film-001" to "Yerli Filmler",
    "$mainUrl/tur/12-gerilim-001" to "Gerilim",
    "$mainUrl/tur/13-komedi-001" to "Komedi",
    "$mainUrl/tur/14-tv-film-001" to "TV Film",
    "$mainUrl/tur/15-belgesel-001" to "Belgesel",
    "$mainUrl/tur/16-aile-001" to "Aile",
    "$mainUrl/tur/17-fantastik-001" to "Fantastik"
)

    // ------------------------------------------------------------
    // ANA SAYFA
    // ------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        /*
         * PARS runtime bazı durumlarda request.data yerine
         * provider mainUrl fallback'ine düşebiliyor.
         *
         * Geçerli bir FilmizleHell liste URL'si gelmezse
         * doğrudan /filmler kullanıyoruz.
         */
        val baseUrl = request.data
            .trim()
            .takeIf {
                it.startsWith(mainUrl) &&
                    (
                        it.contains("/filmler") ||
                        it.contains("/tur/")
                    )
            }
            ?: "$mainUrl/filmler"

        val url = pageUrl(baseUrl, page)

        Log.i(
            TAG,
            "MAIN_PAGE_REQUEST name=${request.name} page=$page url=$url"
        )

        val response = app.get(
            url,
            referer = mainUrl,
            headers = DEFAULT_HEADERS
        )

        val document = response.document

        Log.i(
            TAG,
            "MAIN_PAGE_HTTP url=$url htmlLen=${response.text.length} title=${document.title()}"
        )

        val items = parseMovieCards(document)

        Log.i(
            TAG,
            "MAIN_PAGE_PARSED url=$url items=${items.size}"
        )

        return newHomePageResponse(
            request.name,
            items
        )
    }

    private fun pageUrl(
        baseUrl: String,
        page: Int
    ): String {

        if (page <= 1) {
            return baseUrl
        }

        return if (baseUrl.contains("?")) {
            "$baseUrl&page=$page"
        } else {
            "$baseUrl?page=$page"
        }
    }

    // ------------------------------------------------------------
    // FİLM KARTLARI
    // ------------------------------------------------------------

    private fun parseMovieCards(
        document: Document
    ): List<SearchResponse> {

        val results =
            LinkedHashMap<String, SearchResponse>()

        /*
         * Sitedeki gerçek yapı:
         *
         * <div class="group relative ...">
         *     <img ...>
         *     <a href="/film/....">...</a>
         * </div>
         *
         * Önce gerçek kart divlerini kullanıyoruz.
         */
        document
            .select("div.group.relative")
            .forEach { card ->

                val link =
                    card.selectFirst(
                        "a[href*='/film/']"
                    )
                        ?: return@forEach

                val item =
                    card.toMovieSearchResponse(link)
                        ?: return@forEach

                results.putIfAbsent(
                    item.url,
                    item
                )
            }

        /*
         * Site HTML yapısını değiştirirse ikinci güvenlik katmanı.
         */
        if (results.isEmpty()) {

            Log.w(
                TAG,
                "CARD_SELECTOR_EMPTY trying anchor fallback"
            )

            document
                .select("a[href*='/film/']")
                .forEach { anchor ->

                    val item =
                        anchor.toFallbackMovieSearchResponse()
                            ?: return@forEach

                    results.putIfAbsent(
                        item.url,
                        item
                    )
                }
        }

        Log.i(
            TAG,
            "PARSE_MOVIES cards=${results.size} filmAnchors=${
                document.select("a[href*='/film/']").size
            }"
        )

        return results.values.toList()
    }

    private fun Element.toMovieSearchResponse(
        link: Element
    ): SearchResponse? {

        val href =
            link.attr("href")
                .trim()
                .takeIf {
                    it.contains("/film/")
                }
                ?.let(::fixUrl)
                ?: return null

        val image =
            selectFirst("img")

        val poster =
            image?.let(::posterUrl)

        /*
         * Öncelik:
         * 1. img alt
         * 2. link aria-label
         * 3. kartın altındaki başlık div'i
         */
        val title =
            image
                ?.attr("alt")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: link.attr("aria-label")
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: selectFirst(
                    "div.line-clamp-2"
                )
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        return newMovieSearchResponse(
            cleanTitle(title),
            href,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    /*
     * HTML değişirse kullanılan yedek parser.
     */
    private fun Element.toFallbackMovieSearchResponse():
        SearchResponse? {

        val href =
            attr("href")
                .trim()
                .takeIf {
                    it.contains("/film/")
                }
                ?.let(::fixUrl)
                ?: return null

        var container: Element? = parent()
        var image: Element? = null

        /*
         * Linkin birkaç üst parent'ında poster arıyoruz.
         */
        repeat(5) {

            if (image == null) {
                image =
                    container?.selectFirst("img")
            }

            if (image != null) {
                return@repeat
            }

            container =
                container?.parent()
        }

        val title =
            attr("aria-label")
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?: image
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        return newMovieSearchResponse(
            cleanTitle(title),
            href,
            TvType.Movie
        ) {
            posterUrl =
                image?.let(::posterUrl)
        }
    }

    private fun posterUrl(
        image: Element
    ): String? {

        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src")
        )

        val raw =
            candidates.firstOrNull {
                it.isNotBlank() &&
                    !it.startsWith("data:image")
            }
                ?: return null

        return fixUrl(raw)
    }

    // ------------------------------------------------------------
    // ARAMA
    // ------------------------------------------------------------

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val encoded =
            URLEncoder.encode(
                query,
                Charsets.UTF_8.name()
            )

        val url =
            pageUrl(
                "$mainUrl/arama?q=$encoded",
                page
            )

        Log.i(
            TAG,
            "SEARCH query=$query page=$page url=$url"
        )

        val document =
            app.get(
                url,
                referer = mainUrl,
                headers = DEFAULT_HEADERS
            ).document

        val results =
            parseMovieCards(document)

        val hasNext =
            document.selectFirst(
                "a[rel=next], a[href*='page=${page + 1}']"
            ) != null

        Log.i(
            TAG,
            "SEARCH_DONE query=$query results=${results.size} hasNext=$hasNext"
        )

        return newSearchResponseList(
            results,
            hasNext
        )
    }

    // ------------------------------------------------------------
    // FİLM DETAY
    // ------------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.i(
            TAG,
            "LOAD_BEGIN url=$url"
        )

        val document =
            app.get(
                url,
                referer = mainUrl,
                headers = DEFAULT_HEADERS
            ).document

        val title =
            document
                .selectFirst(
                    "meta[property=og:title]"
                )
                ?.attr("content")
                ?.let(::cleanTitle)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst("h1")
                    ?.text()
                    ?.let(::cleanTitle)
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return null

        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(::fixUrl)

        val description =
            document
                .selectFirst(
                    "meta[name=description]"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        Log.i(
            TAG,
            "LOAD_OK title=$title url=$url"
        )

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ------------------------------------------------------------
    // PLAYTURKA
    // ------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.i(
            TAG,
            "LOAD_LINKS_BEGIN data=$data"
        )

        val response =
            app.get(
                data,
                referer = mainUrl,
                headers = DEFAULT_HEADERS
            )

        val html =
            response.text

        val embeds =
            LinkedHashSet<String>()

        /*
         * https://p.playturka.space/...#VIDEO_ID
         */
        Regex(
            """https://p\.playturka\.space/[^'"\s<>]*#[A-Za-z0-9_-]+""",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .forEach {

                embeds +=
                    it.value
                        .replace(
                            "&amp;",
                            "&"
                        )
                        .trim()
            }

        /*
         * iframe
         */
        response.document
            .select(
                "iframe[src*='p.playturka.space']"
            )
            .forEach { iframe ->

                iframe
                    .attr("src")
                    .replace(
                        "&amp;",
                        "&"
                    )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        embeds.add(
                            fixUrl(it)
                        )
                    }
            }

        /*
         * JS activeSource
         */
        Regex(
            """activeSource\s*:\s*['"]([^'"]*p\.playturka\.space[^'"]*#[A-Za-z0-9_-]+)['"]""",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .forEach {

                embeds +=
                    it.groupValues[1]
                        .replace(
                            "&amp;",
                            "&"
                        )
                        .trim()
            }

        Log.i(
            TAG,
            "LOAD_LINKS_EMBEDS count=${embeds.size} embeds=$embeds"
        )

        if (embeds.isEmpty()) {

            Log.e(
                TAG,
                "LOAD_LINKS_NO_EMBED data=$data htmlLen=${html.length}"
            )

            return false
        }

        var found = false

        embeds.forEach { embedUrl ->

            Log.i(
                TAG,
                "LOAD_EXTRACTOR url=$embedUrl"
            )

            loadExtractor(
                embedUrl,
                data,
                subtitleCallback
            ) { link ->

                found = true

                Log.i(
                    TAG,
                    "EXTRACTOR_LINK_FOUND url=${link.url}"
                )

                callback(link)
            }
        }

        Log.i(
            TAG,
            "LOAD_LINKS_DONE found=$found"
        )

        return found
    }

    // ------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------

    private fun cleanTitle(
        raw: String
    ): String {

        return raw
            .replace(
                Regex(
                    """(?i)\s*(Türkçe\s*(Dublaj|Altyazılı?)\s*)?izle.*$"""
                ),
                ""
            )
            .replace(
                Regex(
                    """\s*\((19|20)\d{2}\)\s*$"""
                ),
                ""
            )
            .trim()
    }

    companion object {

        private const val TAG =
            "FILMIZLEHELL"

        private val DEFAULT_HEADERS =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36",

                "Accept" to
                    "text/html,application/xhtml+xml," +
                    "application/xml;q=0.9,*/*;q=0.8",

                "Accept-Language" to
                    "tr-TR,tr;q=0.9,en;q=0.7",

                "Cache-Control" to
                    "no-cache",

                "Pragma" to
                    "no-cache"
            )
    }
}
