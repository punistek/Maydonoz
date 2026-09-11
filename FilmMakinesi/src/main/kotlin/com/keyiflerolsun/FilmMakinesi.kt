package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class FilmMakinesi : MainAPI() {

    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"

    private fun headers(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    override val mainPage = mainPageOf(
        mainUrl to "Son Filmler",
        "$mainUrl/tur/aksiyon-fmy54y/film/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu",
        "$mainUrl/tur/fantastik-fm1/film/" to "Fantastik",
        "$mainUrl/tur/macera-fm1/film/" to "Macera",
        "$mainUrl/tur/korku-fm2/film/" to "Korku",
        "$mainUrl/ulke/turkiye-fm4/" to "Yerli Filmler",
        "$mainUrl/kanal/netflix-fm1/" to "Netflix",
        "$mainUrl/kanal/amazon/" to "Amazon",
        "$mainUrl/kanal/hbo/" to "HBO"
    )

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s+(?:Filmi\s+)?1080p.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full\s+HD.*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun absoluteUrl(raw: String?): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/${value.trimStart('/')}"
        }
    }

    private fun posterFrom(a: org.jsoup.nodes.Element): String? {
        val img = a.selectFirst(".thumbnail-outer img, img.thumbnail, img") ?: return null

        val candidates = listOf(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original")
        )

        candidates.firstOrNull { it.isNotBlank() }?.let { return absoluteUrl(it) }

        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val first = srcset.split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isNotBlank() }
            if (first != null) return absoluteUrl(first)
        }

        return null
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base

        return when {
            base == mainUrl || base == "$mainUrl/" -> "$mainUrl/filmler-$page/"
            else -> "${base.trimEnd('/')}/sayfa/$page/"
        }
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<SearchResponse>()

        /*
         * FilmMakinesi ana sayfada "Yakında" kartlarını da a.item olarak basıyor.
         * Bunların class'ı "item soon" ve henüz player'ları yok.
         * V1'de en genel a.item selector'ı bunları da aldığı için
         * Street Fighter / Digger / Wildwood gibi yayınsız kartlar uygulamaya girdi.
         *
         * Burada domain/film ismi hardcode etmiyoruz:
         * yalnız sitenin kendi "soon" durumunu eliyoruz.
         */
        document.select("a.item[href*=/film/]:not(.soon)").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("/film/") || !seen.add(href)) return@forEach

            val title = a.attr("data-title").trim()
                .ifBlank { a.selectFirst(".item-footer .title")?.text()?.trim().orEmpty() }
                .ifBlank { a.selectFirst(".item-title")?.text()?.trim().orEmpty() }
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

            if (title.isBlank()) return@forEach

            // Sitedeki gerçek poster alanı:
            // <img src="/uploads/postlar/afis/...webp" class="thumbnail" loading="lazy">
            // data-src varsaymıyoruz; src + srcset + lazy fallback'ları okunuyor.
            val poster = posterFrom(a)

            Log.d(
                "FILMMAKINESI",
                "CARD title=$title poster=${poster ?: "NONE"}"
            )

            val year = a.selectFirst(".item-footer .info span")
                ?.text()?.trim()?.toIntOrNull()

            out += newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }

        Log.i("FILMMAKINESI", "PARSE_CARDS playable=${out.size}")

        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.i("FILMMAKINESI", "MAIN GET section=${request.name} page=$page url=$url")

        val response = app.get(url, headers = headers())
        val items = parseCards(response.document)

        Log.i("FILMMAKINESI", "MAIN DONE items=${items.size}")

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/arama/?s=$q"

        Log.i("FILMMAKINESI", "SEARCH $url")
        val response = app.get(url, headers = headers())
        val items = parseCards(response.document)

        Log.i("FILMMAKINESI", "SEARCH DONE items=${items.size}")
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i("FILMMAKINESI", "LOAD $url")

        val response = app.get(url, headers = headers())
        val document = response.document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val title = cleanTitle(rawTitle)

        val posterRaw = document.selectFirst("meta[property=og:image]")
            ?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                ".info-poster img, .poster img, .before-player img, picture img"
            )?.attr("src")?.trim()

        val poster = absoluteUrl(posterRaw)

        val description = document.selectFirst(".info-description p")
            ?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("""(?:19|20)\d{2}""")
            .find(rawTitle)?.value?.toIntOrNull()

        val trailer = document.selectFirst(".trailer-button[data-video_url]")
            ?.attr("data-video_url")?.trim()
            ?.takeIf { it.isNotBlank() }

        val closeLoad = document.selectFirst(
            ".after-player iframe[data-src*=closeload.filmmakinesi.to], " +
            ".after-player iframe[src*=closeload.filmmakinesi.to]"
        )?.let { it.attr("data-src").ifBlank { it.attr("src") } }

        Log.i(
            "FILMMAKINESI",
            "LOAD title=$title closeLoad=${!closeLoad.isNullOrBlank()} poster=${!poster.isNullOrBlank()}"
        )

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("FILMMAKINESI", "LINKS START detail=$data")

        val response = runCatching {
            app.get(data, headers = headers())
        }.getOrElse {
            Log.e("FILMMAKINESI", "DETAIL FAIL ${it.message}")
            return false
        }

        val embeds = linkedSetOf<String>()

        response.document.select("iframe[data-src], iframe[src]").forEach { iframe ->
            val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            val src = fixUrlNull(raw) ?: return@forEach
            if (src.contains("closeload.filmmakinesi.to/video/embed/", true)) {
                embeds += src
            }
        }

        Regex(
            """https?://closeload\.filmmakinesi\.to/video/embed/[^"'\\\s<]+""",
            RegexOption.IGNORE_CASE
        ).findAll(response.text).forEach {
            embeds += it.value.replace("\\/", "/").replace("&amp;", "&")
        }

        Log.i("FILMMAKINESI", "CLOSELOAD embeds=${embeds.size}")

        var emitted = 0
        for (embed in embeds) {
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { link ->
                    emitted++
                    Log.i(
                        "FILMMAKINESI",
                        "EMIT type=${link.type} quality=${link.quality} host=${runCatching { java.net.URI(link.url).host }.getOrNull()}"
                    )
                    callback(link)
                }
            }.onFailure {
                Log.e("FILMMAKINESI", "CLOSELOAD FAIL ${it::class.simpleName}: ${it.message}")
            }
        }

        Log.i("FILMMAKINESI", "LINKS DONE emitted=$emitted")
        return emitted > 0
    }
}
