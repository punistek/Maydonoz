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
        mainUrl to "Son Filmler"
    )

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""\s+(?:Filmi\s+)?1080p.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full\s+HD.*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<SearchResponse>()

        document.select(
            "#latestmovies a.item[href*=/film/], " +
            ".film-list a.item[href*=/film/], " +
            "a.item[href*=/film/]"
        ).forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!href.contains("/film/") || !seen.add(href)) return@forEach

            val title = a.attr("data-title").trim()
                .ifBlank { a.selectFirst(".item-footer .title")?.text()?.trim().orEmpty() }
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

            if (title.isBlank()) return@forEach

            val img = a.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }

            val year = a.selectFirst(".item-footer .info span")
                ?.text()?.trim()?.toIntOrNull()

            out += newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
                this.year = year
            }
        }

        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/filmler-$page/"
        Log.i("FILMMAKINESI", "MAIN GET $url")

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

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".info-poster img, .poster img")
                ?.attr("src")?.let { fixUrlNull(it) }

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

        Log.i("FILMMAKINESI", "LOAD title=$title closeLoad=${!closeLoad.isNullOrBlank()}")

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
