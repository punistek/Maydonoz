// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = "https://www.fullhdfilmizlesene.now"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    private fun normalizeSiteUrl(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return value

        return value
            .replace("https://fullhdfilmizle.now", mainUrl)
            .replace("http://fullhdfilmizle.now", mainUrl)
            .replace("https://www.fullhdfilmizle.now", mainUrl)
            .replace("http://www.fullhdfilmizle.now", mainUrl)
            .replace("https://fullhdfilmizlesene.now", mainUrl)
            .replace("http://fullhdfilmizlesene.now", mainUrl)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Yeni Filmler",
        "${mainUrl}/en-cok-izlenen-filmler" to "En Çok İzlenen",
        "${mainUrl}/filmizle/bluray-filmler" to "Blu Ray",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "${mainUrl}/filmizle/yerli-filmler" to "Yerli Filmler",
        "${mainUrl}/filmizle/turkce-dublaj-filmler-1" to "Türkçe Dublaj",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePageUrl = normalizeSiteUrl(request.data)
        val pageUrl = when {
            page <= 1 -> basePageUrl
            basePageUrl == "${mainUrl}/" -> "${mainUrl}/yeni-filmler/${page}"
            else -> "${basePageUrl.trimEnd('/')}/${page}"
        }

        val document = app.get(pageUrl).document
        val home = document.select(".film")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d("FHD", "MAIN page=$page url=$pageUrl cards=${home.size}")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val hrefRaw = this.selectFirst("a.tt[href*='/film/']")?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a[href*='/film/']")?.attr("href")
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(hrefRaw) ?: return null

        val localTitle = this.selectFirst(".film-title")?.text()?.trim().orEmpty()
        val foreignTitle = this.selectFirst(".kt")?.text()?.trim().orEmpty()

        val title = when {
            localTitle.isNotBlank() && foreignTitle.isNotBlank() ->
                "$localTitle - $foreignTitle"
            localTitle.isNotBlank() -> localTitle
            else -> this.selectFirst("a.tt")?.text()
                ?.replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
        }

        val image = this.selectFirst("img")
        val posterRaw = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf {
                it.isNotBlank() && !it.startsWith("data:")
            }

        val posterUrl = posterRaw?.let { fixUrlNull(it) }

        val year = this.selectFirst(".film-yil")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=1"
        val document = app.get(searchUrl).document

        return document.select(".film").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val canonicalUrl = normalizeSiteUrl(url)
        Log.d("FHD", "load url » $url")
        Log.d("FHD", "load canonical » $canonicalUrl")

        val document = app.get(canonicalUrl).document

        val title = document.selectFirst(".izle-titles h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.replace(Regex("""\s+Film izle.*$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val posterImage = document.selectFirst(".detay-sol img.ic-afis")
            ?: document.selectFirst(".detay-sol img")

        val poster = fixUrlNull(
            posterImage?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: posterImage?.attr("src")?.takeIf {
                    it.isNotBlank() && !it.startsWith("data:")
                }
                ?: document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
        )

        val year = document
            .selectFirst(".film-info a[href*='/yil/']")
            ?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value }
            ?.toIntOrNull()

        val description = document
            .selectFirst(".ozet-ic")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()

        val tags = document
            .select(".film-info a[rel='category tag'][href*='/filmizle/']")
            .map { it.text().trim().removeSuffix(" Filmleri") }
            .filter { it.isNotBlank() }
            .distinct()

        val score = document
            .selectFirst(".imdb-ic span")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { Score.from10(it) }

        val duration = Regex("""(\d{2,3})\s*(?:dk|dakika)""", RegexOption.IGNORE_CASE)
            .find(document.selectFirst(".detay-sol .sure")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val trailer = document
            .selectFirst(".afis-frg-btn[data-src], .afis-frg-btn[data-url]")
            ?.let {
                it.attr("data-src").ifBlank { it.attr("data-url") }
            }
            ?.takeIf { it.isNotBlank() }

        val actors = document
            .select(".film-info a[href*='/oyuncu/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val recommendations = document
            .select(".izle-swiper-viewport .film, .listelist .film")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != canonicalUrl }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, canonicalUrl, TvType.Movie, canonicalUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = score
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun atob(s: String): String {
        return String(Base64.decode(s, Base64.DEFAULT))
    }

    private fun rtt(s: String): String {
        fun rot13Char(c: Char): Char {
            return when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }

        return s.map { rot13Char(it) }.joinToString("")
    }

    private fun getVideoLinks(document: Document): List<Map<String, String>> {
        // scx artik her zaman script.data() icinde yakalanmiyor.
        // Once script bloklarini, sonra tum HTML'i kontrol ediyoruz.
        val candidates = buildList {
            document.select("script").forEach { script ->
                val data = script.data()
                val html = script.html()
                val outer = script.outerHtml()

                if (data.isNotBlank()) add(data)
                if (html.isNotBlank() && html != data) add(html)
                if (outer.isNotBlank()) add(outer)
            }
            add(document.html())
        }

        var scxData: String? = null

        for (candidate in candidates) {
            val startMatch = Regex(
                """(?:var\s+|let\s+|const\s+)?scx\s*=\s*\{"""
            ).find(candidate) ?: continue

            // Regex ile {.*?} almak nested JSON'da erken kesilebiliyor.
            // Bu nedenle ilk '{' konumundan dengeli parantez taramasi yap.
            val objectStart = candidate.indexOf('{', startMatch.range.first)
            if (objectStart < 0) continue

            var depth = 0
            var inString = false
            var escaped = false
            var quote = '\u0000'
            var objectEnd = -1

            for (i in objectStart until candidate.length) {
                val c = candidate[i]

                if (inString) {
                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (c == '\\') {
                        escaped = true
                        continue
                    }
                    if (c == quote) {
                        inString = false
                    }
                    continue
                }

                if (c == '"' || c == '\'') {
                    inString = true
                    quote = c
                    continue
                }

                when (c) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            objectEnd = i
                            break
                        }
                    }
                }
            }

            if (objectEnd > objectStart) {
                scxData = candidate.substring(objectStart, objectEnd + 1)
                break
            }
        }

        if (scxData.isNullOrBlank()) {
            Log.e("FHD", "SCX bulunamadi. scripts=${document.select("script").size}")
            return emptyList()
        }

        Log.d("FHD", "SCX bulundu len=${scxData.length} data=${scxData.take(300)}")

        val scxMap: SCXData = try {
            jacksonObjectMapper().readValue(scxData)
        } catch (e: Exception) {
            Log.e("FHD", "SCX JSON parse hatasi: ${e.message}", e)
            return emptyList()
        }

        val keys = listOf("atom", "advid", "advidprox", "proton", "fast", "fastly", "tr", "en")
        val linkList = mutableListOf<Map<String, String>>()

        for (key in keys) {
            val t = when (key) {
                "atom"      -> scxMap.atom?.sx?.t
                "advid"     -> scxMap.advid?.sx?.t
                "advidprox" -> scxMap.advidprox?.sx?.t
                "proton"    -> scxMap.proton?.sx?.t
                "fast"      -> scxMap.fast?.sx?.t
                "fastly"    -> scxMap.fastly?.sx?.t
                "tr"        -> scxMap.tr?.sx?.t
                "en"        -> scxMap.en?.sx?.t
                else        -> null
            }

            Log.d("FHD", "SCX key=$key tType=${t?.javaClass?.name ?: "null"} t=$t")

            when (t) {
                is List<*> -> {
                    t.filterIsInstance<String>().forEachIndexed { index, encoded ->
                        try {
                            val decoded = atob(rtt(encoded)).trim()
                            Log.d("FHD", "SCX decode key=$key index=$index -> $decoded")
                            if (decoded.isNotBlank()) {
                                linkList.add(mapOf(key to decoded))
                            }
                        } catch (e: Exception) {
                            Log.e("FHD", "SCX decode hata key=$key index=$index: ${e.message}")
                        }
                    }
                }

                is Map<*, *> -> {
                    t.forEach { (mapKey, value) ->
                        if (value !is String) return@forEach

                        try {
                            val decoded = atob(rtt(value)).trim()
                            Log.d("FHD", "SCX decode key=$key mapKey=$mapKey -> $decoded")
                            if (decoded.isNotBlank()) {
                                linkList.add(
                                    mapOf((mapKey?.toString() ?: key) to decoded)
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("FHD", "SCX map decode hata key=$key mapKey=$mapKey: ${e.message}")
                        }
                    }
                }
            }
        }

        Log.d("FHD", "SCX final links=$linkList")
        return linkList
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FHD", "data » $data")
        val canonicalData = normalizeSiteUrl(data)
        Log.d("FHD", "canonical data » $canonicalData")

        val document    = app.get(canonicalData).document
        val videoLinks = getVideoLinks(document)
        Log.d("FHD", "videoLinks » $videoLinks")
        if (videoLinks.isEmpty()) return false


        for (videoMap in videoLinks) {
            for ((key, value) in videoMap) {
                // Cozulmus scx linki absolute URL ise fixUrlNull'a sokmak gereksiz.
                // Relative link gelirse eski davranisi koru.
                val videoUrl = if (
                    value.startsWith("http://") ||
                    value.startsWith("https://") ||
                    value.startsWith("//")
                ) {
                    if (value.startsWith("//")) "https:$value" else value
                } else {
                    fixUrlNull(value) ?: continue
                }

                val normalizedVideoUrl = when {
                    videoUrl.contains("rapidvid.net/vod/", ignoreCase = true) ->
                        videoUrl
                            .replace("https://www.rapidvid.net", "https://rapidvid.org", ignoreCase = true)
                            .replace("https://rapidvid.net", "https://rapidvid.org", ignoreCase = true)
                            .replace("/vod/", "/vx/", ignoreCase = true)
                    videoUrl.contains("rapidvid.org/vod/", ignoreCase = true) ->
                        videoUrl.replace("/vod/", "/vx/", ignoreCase = true)
                    else -> videoUrl
                }

                Log.d("FHD", "loadExtractor key=$key url=$normalizedVideoUrl")

                if (normalizedVideoUrl.contains("turbo.imgz.me")) {
                    loadExtractor("${key}||${normalizedVideoUrl}", "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(normalizedVideoUrl, canonicalData, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SCXData(
        @JsonProperty("atom")      val atom: AtomData?      = null,
        @JsonProperty("advid")     val advid: AtomData?     = null,
        @JsonProperty("advidprox") val advidprox: AtomData? = null,
        @JsonProperty("proton")    val proton: AtomData?    = null,
        @JsonProperty("fast")      val fast: AtomData?      = null,
        @JsonProperty("fastly")    val fastly: AtomData?    = null,
        @JsonProperty("tr")        val tr: AtomData?        = null,
        @JsonProperty("en")        val en: AtomData?        = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AtomData(
        @JsonProperty("sx") var sx: SXData
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SXData(
        @JsonProperty("t") var t: Any
    )
}
