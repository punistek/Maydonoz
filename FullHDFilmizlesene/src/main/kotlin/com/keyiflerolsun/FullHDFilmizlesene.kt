// PARS FullHDFilmizlesene - V19 browser/session handoff
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
        "${mainUrl}/"                  to "En Yeni Filmler",
        "${mainUrl}/tur/aksiyon"       to "Aksiyon",
        "${mainUrl}/tur/dram"          to "Dram",
        "${mainUrl}/tur/gerilim"       to "Gerilim",
        "${mainUrl}/tur/komedi"        to "Komedi",
        "${mainUrl}/tur/korku"         to "Korku",
        "${mainUrl}/tur/macera"        to "Macera",
        "${mainUrl}/tur/fantastik"     to "Fantastik",
        "${mainUrl}/tur/bilim-kurgu"   to "Bilim Kurgu",
        "${mainUrl}/tur/gizem"         to "Gizem",
        "${mainUrl}/tur/romantik"      to "Romantik",
        "${mainUrl}/tur/suc"           to "Suç",
        "${mainUrl}/tur/savas"         to "Savaş",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePageUrl = normalizeSiteUrl(request.data)
        val pageUrl = when {
            page <= 1 -> basePageUrl
            basePageUrl.endsWith("/") -> "${basePageUrl}sayfa/${page}"
            else -> "${basePageUrl}/sayfa/${page}"
        }

        val document = app.get(pageUrl).document
        val home = document.select("article.movie-card").mapNotNull { it.toSearchResult() }

        Log.d("FHD", "MAIN page=$page url=$pageUrl cards=${home.size}")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".film-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(
            this.selectFirst("a.mc-link")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("a")?.attr("href")
        ) ?: return null

        val image = this.selectFirst("img.mc-afis")
            ?: this.selectFirst("img")

        val posterUrl = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=1"
        val document = app.get(searchUrl).document

        return document.select("article.movie-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val canonicalUrl = normalizeSiteUrl(url)
        Log.d("FHD", "load url » $url")
        Log.d("FHD", "load canonical » $canonicalUrl")

        val document = app.get(canonicalUrl).document

        val title = document.selectFirst(".film-title-h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(".detail-poster img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
        )

        val year = document
            .selectFirst(".film-facts a[href^='/yil/']")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val description = document
            .selectFirst(".detail-synopsis")
            ?.text()
            ?.trim()

        val tags = document
            .select(".film-facts a[href^='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val score = document
            .selectFirst(".ib-score")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { Score.from10(it) }

        val duration = Regex("""(\d{2,3})\s*(?:dk|dakika)""", RegexOption.IGNORE_CASE)
            .find(document.selectFirst(".film-facts")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val trailer = Regex(
            """"(?:embedUrl|trailer)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(document.html())?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        val actors = document
            .select("a[href^='/oyuncu/'], a[href*='/oyuncu/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val recommendations = document
            .select("article.movie-card")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val canonicalData = normalizeSiteUrl(data)

        /*
         * V19 ile doğrulanan gerçek akış:
         *
         * detail page
         * -> localStorage/client.php/session-state.php
         * -> DOM mutation
         * -> RapidVid /vx iframe
         * -> RapidVid JWPlayer
         * -> browser network
         * -> imgscdn... HLS
         *
         * Bu yüzden burada statik _p8/cm/tm çözümü YAPMIYORUZ.
         * Gerçek detail sayfasını PARS Chromium resolver'a teslim ediyoruz.
         */
        Log.d("FHD", "V19_BROWSER_HANDOFF detail=$canonicalData")

        callback.invoke(
            newExtractorLink(
                source = "PARS V19 Browser",
                name = "PARS V19 Browser",
                url = canonicalData,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = canonicalData
                this.headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/153.0.0.0 Safari/537.36",
                    "Referer" to canonicalData,
                    "X-PARS-WEBVIEW" to "1",
                    "X-PARS-DETAIL-REFERER" to canonicalData
                )
                this.quality = Qualities.Unknown.value
            }
        )

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
