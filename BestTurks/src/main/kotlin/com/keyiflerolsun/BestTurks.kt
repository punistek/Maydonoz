package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.UUID

class BestTurks : MainAPI() {

    override var mainUrl = "https://bestturks14.com"
    override var name = "BestTurks"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    /*
     * Kullanıcının verdiği /categories kaynak HTML'inde doğrulanan kategoriler.
     * Video kartları kategori sayfasından dinamik okunur.
     */
    override val mainPage = mainPageOf(
        "/" to "Son Videolar",
        "/categories/turk-ifsa" to "Türk İfşa",
        "/categories/turk-porno" to "Türk Porno",
        "/categories/bedava-porno" to "Bedava Porno"
    )

    private val tag = "BESTTURKS_RESOLVER"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"

    @Volatile
    private var mirrorResolved = false

    private fun htmlHeaders(refererBase: String): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$refererBase/"
    )

    private suspend fun ensureMirror(): String {
        if (mirrorResolved) return mainUrl

        synchronized(this) {
            if (mirrorResolved) return mainUrl
        }

        val candidates = linkedSetOf<String>().apply {
            add("https://bestturks.com")
            add(mainUrl)
            for (n in 14..25) add("https://bestturks$n.com")
        }

        Log.i(tag, "MIRROR_SCAN START candidates=${candidates.size}")

        for (candidate in candidates) {
            try {
                val response = app.get(
                    "$candidate/categories",
                    headers = htmlHeaders(candidate),
                    timeout = 8
                )

                if (response.code !in 200..399) {
                    Log.w(tag, "MIRROR_SCAN FAIL base=$candidate status=${response.code}")
                    continue
                }

                val body = response.text
                val finalUrl = response.url.toString()
                val finalOrigin = originOf(finalUrl)

                val embeddedActive = extractActiveMirrorOrigin(body)
                val chosen = when {
                    !embeddedActive.isNullOrBlank() -> embeddedActive
                    finalOrigin.isNotBlank() -> finalOrigin
                    else -> candidate
                }.trimEnd('/')

                if (chosen.startsWith("https://bestturks")) {
                    mainUrl = chosen
                    mirrorResolved = true
                    Log.i(
                        tag,
                        "MIRROR_SCAN OK candidate=$candidate final=$finalOrigin embedded=$embeddedActive chosen=$mainUrl"
                    )
                    return mainUrl
                }
            } catch (t: Throwable) {
                Log.w(
                    tag,
                    "MIRROR_SCAN EXCEPTION base=$candidate type=${t::class.java.simpleName} msg=${t.message}"
                )
            }
        }

        mirrorResolved = true
        Log.w(tag, "MIRROR_SCAN FALLBACK mainUrl=$mainUrl")
        return mainUrl
    }

    private fun extractActiveMirrorOrigin(html: String): String? {
        val patterns = listOf(
            Regex("""activeMirrorOrigin\\?":\\?"(https?:\\?/\\?/bestturks\d*\.com)"""),
            Regex("""activeMirrorOrigin["']?\s*[:=]\s*["'](https?://bestturks\d*\.com)""")
        )

        for (regex in patterns) {
            val raw = regex.find(html)?.groupValues?.getOrNull(1).orEmpty()
            if (raw.isNotBlank()) {
                return decodeEscaped(raw)
            }
        }

        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val trace = traceId()
        val base = ensureMirror()

        val path = request.data
        val pageUrl = if (page <= 1) {
            "$base$path"
        } else {
            val sep = if (path.contains("?")) "&" else "?"
            "$base$path${sep}page=$page"
        }

        Log.i(
            tag,
            "[$trace] MAIN START name='${request.name}' page=$page url=$pageUrl"
        )

        return try {
            val response = app.get(
                pageUrl,
                headers = htmlHeaders(base)
            )

            val html = response.text
            Log.i(
                tag,
                "[$trace] MAIN GET status=${response.code} final=${response.url} len=${html.length}"
            )

            updateMirrorFromResponse(response.url.toString(), html)

            val items = parseVideoCards(
                html = html,
                pageUrl = response.url.toString()
            )

            Log.i(tag, "[$trace] MAIN DONE items=${items.size}")

            newHomePageResponse(
                request.name,
                items,
                hasNext = items.isNotEmpty()
            )
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] MAIN EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trace = traceId()
        val base = ensureMirror()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$base/search?q=$q"

        Log.i(tag, "[$trace] SEARCH query='$query' url=$url")

        return try {
            val response = app.get(url, headers = htmlHeaders(base))
            updateMirrorFromResponse(response.url.toString(), response.text)
            val items = parseVideoCards(response.text, response.url.toString())
            Log.i(tag, "[$trace] SEARCH DONE items=${items.size}")
            items
        } catch (t: Throwable) {
            Log.e(tag, "[$trace] SEARCH FAIL ${t.message}", t)
            emptyList()
        }
    }

    private fun parseVideoCards(
        html: String,
        pageUrl: String
    ): List<SearchResponse> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = linkedMapOf<String, SearchResponse>()

        doc.select("a[href*=/video/]").forEach { a ->
            try {
                val hrefRaw = a.attr("href").trim()
                if (hrefRaw.isBlank()) return@forEach

                val href = absoluteUrl(pageUrl, hrefRaw)
                if (!href.contains("/video/")) return@forEach
                if (out.containsKey(href)) return@forEach

                val img = a.selectFirst("img")

                val title =
                    a.selectFirst("h1,h2,h3,h4")
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.length >= 2 }
                        ?: img?.attr("alt")
                            ?.trim()
                            ?.takeIf { it.length >= 2 }
                        ?: a.attr("title")
                            .trim()
                            .takeIf { it.length >= 2 }
                        ?: a.text()
                            .trim()
                            .takeIf { it.length in 2..220 }
                        ?: return@forEach

                val posterRaw =
                    listOf(
                        img?.attr("src"),
                        img?.attr("data-src"),
                        img?.attr("data-lazy-src")
                    )
                        .firstOrNull { !it.isNullOrBlank() }
                        ?.trim()

                val poster = posterRaw?.let {
                    normalizeNextImage(absoluteUrl(pageUrl, it))
                }

                out[href] = newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    posterUrl = poster
                }
            } catch (_: Throwable) {
                // Bir bozuk kart tüm listeyi bozmasın.
            }
        }

        /*
         * Next.js Flight verisinde kart URL'leri bazen HTML anchor olarak değil
         * kaçışlı JSON içinde bulunabiliyor. Anchor seçici boş kaldığında
         * slugları ham kaynaktan da topluyoruz.
         */
        if (out.isEmpty()) {
            val slugRegex = Regex("""(?:https?:\\?/\\?/bestturks\d*\.com)?\\?/video\\?/([a-z0-9\-]+)""")
            slugRegex.findAll(html).forEach { m ->
                val slug = m.groupValues.getOrNull(1).orEmpty()
                if (slug.isBlank()) return@forEach

                val href = "${originOf(pageUrl)}/video/$slug"
                if (!out.containsKey(href)) {
                    out[href] = newMovieSearchResponse(
                        slugToTitle(slug),
                        href,
                        TvType.Movie
                    )
                }
            }
        }

        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val trace = traceId()
        val base = ensureMirror()

        Log.i(tag, "[$trace] LOAD START url=$url")

        val response = app.get(
            normalizeSiteUrl(url, base),
            headers = htmlHeaders(base)
        )

        val html = response.text
        updateMirrorFromResponse(response.url.toString(), html)

        Log.i(
            tag,
            "[$trace] LOAD GET status=${response.code} final=${response.url} len=${html.length}"
        )

        if (response.code !in 200..399 || html.isBlank()) {
            Log.e(tag, "[$trace] LOAD invalid response")
            return null
        }

        val finalUrl = response.url.toString()
        val doc = Jsoup.parse(html, finalUrl)

        val title =
            doc.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: doc.title()
                    .substringBefore("|")
                    .trim()
                    .ifBlank { "BestTurks" }

        val poster =
            extractPoster(html)
                ?: doc.selectFirst("video[poster]")?.attr("poster")?.trim()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        val plot =
            doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val tags =
            doc.select("a[href*=/categories/]")
                .map { it.text().trim().removePrefix("#") }
                .filter { it.isNotBlank() }
                .distinct()

        Log.i(
            tag,
            "[$trace] LOAD parsed title='$title' poster=${!poster.isNullOrBlank()} categories=${tags.size}"
        )

        return newMovieLoadResponse(
            title,
            finalUrl,
            TvType.Movie,
            finalUrl
        ) {
            posterUrl = poster?.let { absoluteUrl(finalUrl, it) }
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trace = traceId()
        val base = ensureMirror()
        val detailUrl = normalizeSiteUrl(data, base)

        Log.i(tag, "[$trace] ========================================")
        Log.i(tag, "[$trace] RESOLVE START detail=$detailUrl casting=$isCasting")

        return try {
            val response = app.get(
                detailUrl,
                headers = htmlHeaders(base)
            )

            val html = response.text
            val finalDetail = response.url.toString()
            updateMirrorFromResponse(finalDetail, html)

            Log.i(
                tag,
                "[$trace] DETAIL status=${response.code} final=$finalDetail len=${html.length}"
            )

            val stream = extractStreamUrl(html)
            if (stream.isNullOrBlank()) {
                Log.e(tag, "[$trace] STREAM_URL BULUNAMADI")
                return false
            }

            val activeBase = originOf(finalDetail).ifBlank { mainUrl }
            val cookie = buildHlsCookie(stream)

            Log.i(
                tag,
                "[$trace] STREAM FOUND host=${hostOf(stream)} path=${pathOf(stream)} cookiePresent=${cookie.isNotBlank()}"
            )

            val hlsHeaders = linkedMapOf(
                "User-Agent" to ua,
                "Accept" to "*/*",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Origin" to activeBase,
                "Referer" to "$activeBase/"
            )

            if (cookie.isNotBlank()) {
                hlsHeaders["Cookie"] = cookie
            }

            /*
             * Kullanıcının ağ kaydında master.m3u8 cevapları göreli kalite
             * playlistleri (720p/index.m3u8 vb.) döndürüyor. Query tokenı çocuk
             * URL'lere taşınmayabileceği için hls_* cookie'lerini callback
             * headerında da gönderiyoruz.
             */
            callback(
                newExtractorLink(
                    source = "BestTurks",
                    name = "BestTurks HLS",
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$activeBase/"
                    headers = hlsHeaders
                    quality = Qualities.Unknown.value
                }
            )

            Log.i(tag, "[$trace] HLS CALLBACK OK")
            Log.i(tag, "[$trace] ========================================")
            true
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] RESOLVE EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            false
        }
    }

    private fun extractStreamUrl(html: String): String? {
        val decoded = decodeEscaped(html)

        val patterns = listOf(
            Regex("""["']streamUrl["']\s*:\s*["'](https?://[^"'<>]+?\.m3u8[^"'<>]*)["']"""),
            Regex("""streamUrl\\?":\\?"(https?:\\?/\\?/[^"]+?\.m3u8[^"]*)"""),
            Regex("""(https?://bestturkz\.com/videos/[A-Za-z0-9_\-/]+/(?:master|\d+p/index)\.m3u8\?[^"'<>\\\s]+)"""),
            Regex("""(https?:\\?/\\?/bestturkz\.com/videos/[A-Za-z0-9_\-/]+/(?:master|\d+p/index)\.m3u8\?[^"\\\s]+)""")
        )

        for ((i, regex) in patterns.withIndex()) {
            val raw = regex.find(decoded)?.groupValues?.getOrNull(1).orEmpty()
            if (raw.isBlank()) continue

            val url = decodeEscaped(raw)
                .replace("&amp;", "&")
                .trim()

            if (url.startsWith("http") && ".m3u8" in url) {
                Log.i(tag, "STREAM_REGEX_MATCH pattern=$i")
                return url
            }
        }

        return null
    }

    private fun extractPoster(html: String): String? {
        val decoded = decodeEscaped(html)
        val patterns = listOf(
            Regex("""["']posterUrl["']\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""posterUrl\\?":\\?"(https?:\\?/\\?/[^"]+)"""),
            Regex("""poster=["'](https?://[^"']+)["']""")
        )

        for (regex in patterns) {
            val raw = regex.find(decoded)?.groupValues?.getOrNull(1).orEmpty()
            if (raw.isNotBlank()) return decodeEscaped(raw).replace("&amp;", "&")
        }
        return null
    }

    private fun buildHlsCookie(url: String): String {
        val e = queryParam(url, "e")
        val st = queryParam(url, "st")
        val k = queryParam(url, "k")
        val pv = queryParam(url, "pv")
        val pa = queryParam(url, "pa")

        val parts = mutableListOf<String>()
        if (k.isNotBlank()) parts += "hls_k=$k"
        if (pv.isNotBlank()) parts += "hls_pv=$pv"
        if (pa.isNotBlank()) parts += "hls_pa=$pa"
        if (st.isNotBlank()) parts += "hls_st=$st"
        if (e.isNotBlank()) parts += "hls_e=$e"

        return parts.joinToString("; ")
    }

    private fun queryParam(url: String, key: String): String {
        val regex = Regex("""(?:\?|&)$key=([^&]+)""")
        val raw = regex.find(url)?.groupValues?.getOrNull(1).orEmpty()
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            raw
        }
    }

    private fun updateMirrorFromResponse(finalUrl: String, html: String) {
        val embedded = extractActiveMirrorOrigin(html)
        val finalOrigin = originOf(finalUrl)

        val chosen = when {
            !embedded.isNullOrBlank() -> embedded
            finalOrigin.startsWith("https://bestturks") -> finalOrigin
            else -> ""
        }.trimEnd('/')

        if (chosen.isNotBlank() && chosen != mainUrl) {
            Log.i(tag, "MIRROR_UPDATE old=$mainUrl new=$chosen")
            mainUrl = chosen
        }
    }

    private fun normalizeSiteUrl(url: String, activeBase: String): String {
        val trimmed = url.trim()

        if (trimmed.startsWith("/")) {
            return activeBase.trimEnd('/') + trimmed
        }

        if (trimmed.startsWith("http")) {
            return if (
                trimmed.contains("bestturks") &&
                !trimmed.startsWith(activeBase)
            ) {
                val pathStart = trimmed.indexOf('/', trimmed.indexOf("://") + 3)
                if (pathStart >= 0) {
                    activeBase.trimEnd('/') + trimmed.substring(pathStart)
                } else {
                    activeBase
                }
            } else {
                trimmed
            }
        }

        return activeBase.trimEnd('/') + "/" + trimmed.trimStart('/')
    }

    private fun absoluteUrl(baseUrl: String, raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return value
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"

        val origin = originOf(baseUrl)
        return if (value.startsWith("/")) {
            origin + value
        } else {
            origin + "/" + value
        }
    }

    private fun normalizeNextImage(url: String): String {
        if (!url.contains("/_next/image?")) return url

        val encoded = queryParam(url, "url")
        return if (encoded.startsWith("http")) encoded else url
    }

    private fun originOf(url: String): String {
        return Regex("""^(https?://[^/]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun hostOf(url: String): String {
        return Regex("""^https?://([^/]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun pathOf(url: String): String {
        return Regex("""^https?://[^/]+(/[^?]*)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun decodeEscaped(input: String): String {
        return input
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }

    private fun slugToTitle(slug: String): String {
        return slug
            .split("-")
            .joinToString(" ") { part ->
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase() else c.toString()
                }
            }
    }

    private fun traceId(): String =
        UUID.randomUUID().toString().replace("-", "").take(8)
}
