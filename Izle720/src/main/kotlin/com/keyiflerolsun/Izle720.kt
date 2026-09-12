package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class Izle720 : MainAPI() {

    override var mainUrl = "https://720izle.com"
    override var name = "720izle"
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
        mainUrl to "Son Eklenen Filmler",
        "$mainUrl/kategori/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/kategori/yerli-filmler/" to "Yerli Filmler",
        "$mainUrl/kategori/turkce-netflix-filmleri-izle/" to "Netflix Filmleri",
        "$mainUrl/kategori/aksiyon/" to "Aksiyon",
        "$mainUrl/kategori/macera-filmleri/" to "Macera"
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\|\s*720p\s*izle.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*720p\s*izle.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun posterFrom(element: org.jsoup.nodes.Element): String? {
        val img = element.selectFirst("img") ?: return null

        return img.attr("data-src")
            .takeIf { it.startsWith("http") }
            ?: img.attr("data-lazy-src")
                .takeIf { it.startsWith("http") }
            ?: img.attr("srcset")
                .substringBefore(",")
                .trim()
                .substringBefore(" ")
                .takeIf { it.startsWith("http") }
            ?: img.attr("src")
                .takeIf { it.startsWith("http") }
    }

    private fun parseMovieCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<SearchResponse>()

        val anchors = document.select(
            "div.slide-item a[href*=/filmler11/], " +
                "div.item a[href*=/filmler11/], " +
                "article a[href*=/filmler11/], " +
                "a[href*=/filmler11/]"
        )

        for (a in anchors) {
            val href = fixUrlNull(a.attr("href")) ?: continue
            if (!href.contains("/filmler11/")) continue
            if (!seen.add(href)) continue

            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
                ?: continue

            val title = (
                img.attr("alt").trim()
                    .ifBlank { a.attr("title").trim() }
                    .ifBlank { a.selectFirst("h2,h3,.title")?.text()?.trim().orEmpty() }
                )

            if (title.isBlank()) continue

            out += newMovieSearchResponse(cleanTitle(title), href, TvType.Movie) {
                this.posterUrl = posterFrom(a)
                    ?: a.parent()?.let { posterFrom(it) }
            }
        }

        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val baseUrl = request.data.trimEnd('/')

        val url = if (page <= 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/page/$page/"
        }

        Log.i(
            "IZLE720",
            "MAIN_PAGE GET category=${request.name} page=$page url=$url"
        )

        val response = runCatching {
            app.get(url, headers = headers())
        }.getOrElse {
            Log.e(
                "IZLE720",
                "MAIN_PAGE FAIL category=${request.name} page=$page " +
                    "${it::class.simpleName}: ${it.message}"
            )

            return newHomePageResponse(
                request.name,
                emptyList(),
                false
            )
        }

        val items = parseMovieCards(response.document)

        Log.i(
            "IZLE720",
            "MAIN_PAGE DONE category=${request.name} page=$page " +
                "items=${items.size} url=$url"
        )

        return newHomePageResponse(
            request.name,
            items,
            items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$q"

        Log.i("IZLE720", "SEARCH query=$query url=$url")

        val response = app.get(url, headers = headers())
        val items = parseMovieCards(response.document)

        Log.i("IZLE720", "SEARCH DONE items=${items.size}")

        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i("IZLE720", "LOAD url=$url")

        val response = app.get(url, headers = headers())
        val document = response.document

        val rawTitle =
            document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")?.trim()
                ?: return null

        val title = cleanTitle(rawTitle)

        val poster =
            document.selectFirst(".single-content.movie .poster img")
                ?.let { posterFrom(it) }
                ?: document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.takeIf { it.startsWith("http") }

        val description =
            document.selectFirst("meta[name=description]")
                ?.attr("content")?.trim()
                ?: document.selectFirst("meta[property=og:description]")
                    ?.attr("content")?.trim()
                ?: document.selectFirst(".movie-detail, .movie-description, .description")
                    ?.text()?.trim()

        val year =
            Regex("""(?:19|20)\d{2}""")
                .find(title)
                ?.value
                ?.toIntOrNull()

        val trailer =
            document.selectFirst(
                "iframe[src*=youtube.com], iframe[src*=youtube-nocookie.com], iframe[src*=youtu.be]"
            )?.attr("src")
                ?.takeIf { it.isNotBlank() }

        val hotstreamCount =
            document.select("iframe[src*=hotstream.club/embed/]").size

        Log.i(
            "IZLE720",
            "LOAD DONE title=$title year=$year hotstreamIframeCount=$hotstreamCount"
        )

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
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
        Log.i("IZLE720", "LOAD_LINKS START detail=$data")

        val response = runCatching {
            app.get(data, headers = headers())
        }.getOrElse {
            Log.e(
                "IZLE720",
                "DETAIL GET FAIL ${it::class.simpleName}: ${it.message}"
            )
            return false
        }

        val document = response.document

        val embeds = linkedSetOf<String>()

        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (
                src.contains("hotstream.club/embed/", ignoreCase = true)
            ) {
                embeds += src
            }
        }

        // Safety fallback: future theme changes may print the iframe URL in JS.
        Regex(
            """https?://(?:www\.)?hotstream\.club/embed/[^"'\\s<]+""",
            RegexOption.IGNORE_CASE
        ).findAll(response.text).forEach { match ->
            embeds += match.value
                .replace("\\/", "/")
                .replace("&amp;", "&")
        }

        Log.i(
            "IZLE720",
            "LOAD_LINKS embeds=${embeds.size} " +
                embeds.joinToString()
        )

        if (embeds.isEmpty()) {
            Log.e("IZLE720", "LOAD_LINKS Hotstream iframe bulunamadi")
            return false
        }

        var emitted = 0

        for (embed in embeds) {
            var sourceEmitted = 0

            Log.i("IZLE720", "HOTSTREAM TRY $embed")

            runCatching {
                loadExtractor(
                    embed,
                    data,
                    subtitleCallback
                ) { link ->
                    sourceEmitted++
                    emitted++

                    Log.i(
                        "IZLE720",
                        "HOTSTREAM LINK type=${link.type} quality=${link.quality}"
                    )

                    callback(link)
                }
            }.onFailure {
                Log.e(
                    "IZLE720",
                    "HOTSTREAM FAIL ${it::class.simpleName}: ${it.message}"
                )
            }

            Log.i(
                "IZLE720",
                "HOTSTREAM RESULT emitted=$sourceEmitted"
            )
        }

        Log.i("IZLE720", "LOAD_LINKS DONE emitted=$emitted")

        return emitted > 0
    }
}
