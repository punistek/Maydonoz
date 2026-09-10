package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SpankBang : MainAPI() {

    override var mainUrl = "https://spankbang.com"
    override var name = "SpankBang"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/152.0.0.0 Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,tr;q=0.8",
        "Cookie" to "age_pass=1; cookie_consent_required=0; show_cookie_consent_modal=0"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/new_videos/" to "New",
        "$mainUrl/trending_videos/" to "Trend",
        "$mainUrl/most_popular/" to "Popular",
        "$mainUrl/s/onlyfans/" to "OnlyFans",
        "$mainUrl/s/milf/" to "MILF",
        "$mainUrl/s/amateur/" to "Amateur",
        "$mainUrl/s/asian/" to "Asian",
        "$mainUrl/s/anal/" to "Anal",
        "$mainUrl/s/big+tits/" to "Big Tits",
        "$mainUrl/s/teen/" to "Teen",
        "$mainUrl/s/lesbian/" to "Lesbian",
        "$mainUrl/s/creampie/" to "Creampie"
    )

    private fun pageUrl(base: String, page: Int): String {
        val clean = base.trimEnd('/')

        // Kategori URL'sini ilk sayfada AYNEN kullan.
        // Örn:
        // https://spankbang.com/s/amateur/
        // https://spankbang.com/s/asian/
        // https://spankbang.com/s/anal/
        // https://spankbang.com/s/big+tits/
        // https://spankbang.com/s/teen/
        return if (page <= 1) {
            "$clean/"
        } else {
            "$clean/$page/"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.d("SkBg", "MAIN_START name=${request.name} page=$page url=$url")

        val response = runCatching {
            app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/"
            )
        }.onFailure {
            Log.e("SkBg", "MAIN_HTTP_ERROR name=${request.name} page=$page url=$url", it)
        }.getOrThrow()

        val html = response.text
        val document = response.document

        Log.d(
            "SkBg",
            "MAIN_HTTP name=${request.name} page=$page code=${response.code} " +
                "final=${response.url} htmlLen=${html.length}"
        )

        Log.d(
            "SkBg",
            "MAIN_TITLE name=${request.name} title=${document.title()}"
        )

        val selectorOld = document.select("div.main_results div.video-item, div.video-item")
        val selectorNew = document.select("[data-testid=video-item]")
        val selectorAnyVideo = document.select("a[href*='/video/']")

        Log.d(
            "SkBg",
            "MAIN_SELECTORS name=${request.name} old=${selectorOld.size} " +
                "new=${selectorNew.size} videoLinks=${selectorAnyVideo.size}"
        )

        selectorAnyVideo.firstOrNull()?.let { firstLink ->
            Log.d(
                "SkBg",
                "MAIN_FIRST_LINK name=${request.name} href=${firstLink.attr("href")} " +
                    "title=${firstLink.attr("title")} text=${firstLink.text().take(120)}"
            )
        }

        val firstImg = document.selectFirst("[data-testid=video-item] picture img, [data-testid=video-item] img, div.video-item picture img, div.video-item img")
        if (firstImg != null) {
            Log.d(
                "SkBg",
                "MAIN_FIRST_IMG name=${request.name} src=${firstImg.attr("src")} " +
                    "dataSrc=${firstImg.attr("data-src")} " +
                    "dataOriginal=${firstImg.attr("data-original")} " +
                    "dataLazy=${firstImg.attr("data-lazy-src")} " +
                    "alt=${firstImg.attr("alt").take(120)}"
            )
        } else {
            Log.w("SkBg", "MAIN_FIRST_IMG name=${request.name} NONE")
        }

        val cards = if (selectorNew.isNotEmpty()) selectorNew else selectorOld

        val home = cards
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d(
            "SkBg",
            "MAIN_RESULT name=${request.name} page=$page cards=${cards.size} items=${home.size}"
        )

        home.firstOrNull()?.let { first ->
            Log.d(
                "SkBg",
                "MAIN_FIRST_RESULT name=${request.name} title=${first.name} " +
                    "url=${first.url} poster=${first.posterUrl}"
            )
        }

        if (home.isEmpty()) {
            val snippet = html
                .replace("\n", " ")
                .replace("\r", " ")
                .take(1000)
            Log.w("SkBg", "MAIN_EMPTY_HTML name=${request.name} snippet=$snippet")
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst(
            "div.name-and-menu-wrapper a[href*='/video/'], p a[href*='/video/'], a[href*='/video/']"
        )

        if (link == null) {
            Log.w("SkBg", "ITEM_SKIP noVideoLink html=${outerHtml().take(400)}")
            return null
        }

        val hrefRaw = link.attr("href")
        val href = fixUrlNull(hrefRaw)

        if (href == null || !href.contains("/video/")) {
            Log.w("SkBg", "ITEM_SKIP badHref raw=$hrefRaw fixed=$href")
            return null
        }

        val img = selectFirst("picture img, img")

        val titleRaw = link.attr("title")
            .ifBlank { link.text() }
            .ifBlank { img?.attr("alt").orEmpty() }

        val title = fixTitle(titleRaw)

        if (title.isBlank()) {
            Log.w("SkBg", "ITEM_SKIP blankTitle href=$href html=${outerHtml().take(400)}")
            return null
        }

        val posterRaw = listOf(
            img?.attr("src"),
            img?.attr("data-src"),
            img?.attr("data-original"),
            img?.attr("data-lazy-src")
        ).firstOrNull { !it.isNullOrBlank() }

        val poster = fixUrlNull(posterRaw)

        Log.d(
            "SkBg",
            "ITEM_OK title=$title href=$href posterRaw=$posterRaw poster=$poster"
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val safeQuery = query.trim().replace(" ", "+")

        for (page in 1..5) {
            val base = "$mainUrl/s/$safeQuery/"
            val url = if (page == 1) {
                base
            } else {
                "${base}${page}/"
            }

            val document = app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/"
            ).document

            val pageResults = document
                .select("div.main_results div.video-item, div.video-item")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (pageResults.isEmpty()) break

            val before = results.size
            pageResults.forEach { item ->
                if (results.none { it.url == item.url }) results.add(item)
            }
            if (results.size == before) break
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = pageHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("div#video h1, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description =
            document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: title

        val year = Regex(""""uploadDate"\s*:\s*"(\d{4})""")
            .find(document.html())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()

        val tags = document.select(
            "div.searches a, a[href*='/s/']"
        ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val duration = document.selectFirst("meta[property=og:duration]")
            ?.attr("content")?.toIntOrNull()?.div(60)

        val recommendations = document
            .select("section.user_uploads div.video-item, div.video-item")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(20)

        val actors = document.select("li.primary_actions_container").mapNotNull {
            val actorName = it.selectFirst("span.name")?.text()?.trim()
                ?.takeIf { name -> name.isNotBlank() }
                ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun decodeJsUrl(raw: String): String {
        return raw
            .trim()
            .trim('"', '\'', '[', ']', ' ')
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\x26", "&", ignoreCase = true)
            .replace("&amp;", "&")
    }

    private fun qualityFrom(label: String, url: String): Int {
        val text = "$label $url"
        val q = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return when (q) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun extractStaticStreams(html: String): List<Pair<String, String>> {
        val streams = linkedMapOf<String, String>()

        // Güncel SpankBang / yt-dlp mantığı:
        // stream_url_720p = 'https://...mp4?...'
        Regex(
            """(?i)stream_url_([A-Za-z0-9_-]+)\s*=\s*["']([^"']+)["']"""
        ).findAll(html).forEach { match ->
            val label = match.groupValues[1]
            val url = decodeJsUrl(match.groupValues[2])
            if (url.startsWith("http")) streams[url] = label
        }

        // Bazı sayfalarda obje/json biçiminde kalite -> URL.
        Regex(
            """(?i)["']?((?:2160|1440|1080|720|480|360|240)p?|m3u8[^"':,\s]*)["']?\s*:\s*(?:\[\s*)?["']([^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']"""
        ).findAll(html).forEach { match ->
            val label = match.groupValues[1]
            val url = decodeJsUrl(match.groupValues[2])
            if (url.startsWith("http")) streams[url] = label
        }

        // Son fallback: HTML/JS içinde geçen signed MP4/HLS URL'lerini yakala.
        Regex(
            """https?:\\?/\\?/[^"'\\\s<>]+?\.(?:mp4|m3u8|mpd)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { match ->
            val url = decodeJsUrl(match.value)
            if (url.startsWith("http")) streams.putIfAbsent(url, "Direct")
        }

        return streams.map { it.key to it.value }
    }

    private suspend fun extractViaStreamApi(
        html: String,
        referer: String
    ): List<Pair<String, String>> {
        val streamKey = Regex(
            """data-streamkey\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1) ?: return emptyList()

        Log.d("SkBg", "streamKey » $streamKey")

        val response = app.post(
            "$mainUrl/api/videos/stream",
            data = mapOf(
                "id" to streamKey,
                "data" to "0"
            ),
            headers = pageHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            referer = referer
        )

        val body = response.text
        Log.d("SkBg", "streamApi code=${response.code} len=${body.length}")

        val streams = linkedMapOf<String, String>()

        // {"720p":["https://...mp4"],"1080p":["..."]}
        Regex(
            """"([^"]+)"\s*:\s*\[\s*"([^"]+)""""
        ).findAll(body).forEach { match ->
            val label = match.groupValues[1]
            val url = decodeJsUrl(match.groupValues[2])
            if (url.startsWith("http")) streams[url] = label
        }

        // {"720p":"https://...mp4"}
        Regex(
            """"([^"]+)"\s*:\s*"([^"]+\.(?:mp4|m3u8|mpd)[^"]*)"""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { match ->
            val label = match.groupValues[1]
            val url = decodeJsUrl(match.groupValues[2])
            if (url.startsWith("http")) streams[url] = label
        }

        return streams.map { it.key to it.value }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SkBg", "data » $data")

        val response = app.get(
            data,
            headers = pageHeaders,
            referer = "$mainUrl/"
        )

        val html = response.text

        var streams = extractStaticStreams(html)

        if (streams.isEmpty()) {
            streams = runCatching {
                extractViaStreamApi(html, data)
            }.onFailure {
                Log.e("SkBg", "stream api error", it)
            }.getOrDefault(emptyList())
        }

        if (streams.isEmpty()) {
            Log.e("SkBg", "No playable stream found")
            return false
        }

        Log.d("SkBg", "streams » ${streams.size}")

        streams
            .distinctBy { it.first }
            .sortedByDescending { qualityFrom(it.second, it.first) }
            .forEach { (videoUrl, label) ->
                val quality = qualityFrom(label, videoUrl)
                val displayName = if (quality == Qualities.Unknown.value) {
                    "$name ${label.takeIf { it.isNotBlank() } ?: "Direct"}"
                } else {
                    "$name ${quality}p"
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = displayName,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        referer = "$mainUrl/"
                        this.quality = quality
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "$mainUrl/",
                            "Accept" to "*/*"
                        )
                    }
                )
            }

        return true
    }
}
