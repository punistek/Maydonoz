package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class Sinefy : MainAPI() {
    override var mainUrl = "https://sinefy3.com"
    override var name = "Sinefy"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "/" to "Son Eklenen Filmler",
        "/dizi-izle" to "Yabancı Diziler",
        "/gozat/filmler/aksiyon" to "Aksiyon",
        "/gozat/filmler/bilim-kurgu" to "Bilim Kurgu",
        "/gozat/filmler/komedi" to "Komedi",
        "/gozat/filmler/korku" to "Korku",
        "/gozat/filmler/macera" to "Macera",
        "/seri-filmler" to "Seri Filmler"
    )

    private fun Element.bestImage(): String? {
        val img = if (tagName() == "img") this else selectFirst("img") ?: return null
        return listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { key -> img.attr(key).trim().takeIf { it.isNotBlank() } }
            ?.let(::fixUrl)
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\\s+(izle|full\\s*hd\\s*izle)$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun Element.toCard(): SearchResponse? {
        val a = when {
            tagName() == "a" -> this
            else -> selectFirst("a[href]") ?: return null
        }
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        val absolute = fixUrl(href)

        val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault(absolute)
        val isEpisode = Regex("""/izle/[^/]+/sezon-\\d+/bolum-\\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)
        val isWatch = path.startsWith("/izle/")
        val isSeries = path.contains("/dizi", true) || path.contains("/yabanci-dizi", true)
        if (!isWatch && !isSeries) return null

        val title = cleanTitle(
            a.attr("title").takeIf { it.isNotBlank() }
                ?: selectFirst("h1,h2,h3,h4,h5,.title,.name,.movie-title,.series-title")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: a.text().takeIf { it.isNotBlank() }
                ?: return null
        )
        if (title.length < 2) return null

        val poster = bestImage()
        return if (isEpisode || isSeries) {
            newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, absolute, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun cards(document: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        document.select("a[href*=/izle/], a[href*=/dizi], article, .movie, .series, .item, .card")
            .forEach { el -> el.toCard()?.let { out[it.url] = it } }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = fixUrl(request.data)
        val url = if (page <= 1) base else {
            val sep = if (base.contains("?")) "&" else "?"
            "$base${sep}page=$page"
        }
        val items = cards(app.get(url, headers = headers()).document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        // Sinefy'nin arama endpoint'i elimizdeki ağ kaydında kanıtlanmadığı için URL uydurmuyoruz.
        // Site ana film/dizi kataloglarında eşleşme yapıyoruz.
        val needle = q.lowercase()
        val out = LinkedHashMap<String, SearchResponse>()
        listOf("/", "/dizi-izle").forEach { path ->
            val doc = runCatching { app.get(fixUrl(path), headers = headers()).document }.getOrNull() ?: return@forEach
            cards(doc).filter { it.name.lowercase().contains(needle) }.forEach { out[it.url] = it }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers()).document
        val title = cleanTitle(
            doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: return null
        )
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let(::fixUrl)
            ?: doc.selectFirst("img")?.bestImage()
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".description,.overview,.summary,.content p")?.text()?.trim()
        val year = Regex("""\\b(19|20)\\d{2}\\b""").find(doc.text())?.value?.toIntOrNull()

        val episodeLinks = LinkedHashMap<String, Episode>()
        doc.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            val m = Regex("""/sezon-(\\d+)/bolum-(\\d+)""", RegexOption.IGNORE_CASE).find(href) ?: return@forEach
            val season = m.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = m.groupValues[2].toIntOrNull() ?: return@forEach
            episodeLinks[href] = newEpisode(href) {
                this.name = a.text().trim().takeIf { it.isNotBlank() } ?: "$season. Sezon $episode. Bölüm"
                this.season = season
                this.episode = episode
            }
        }

        val currentEp = Regex("""/sezon-(\\d+)/bolum-(\\d+)""", RegexOption.IGNORE_CASE).find(url)
        if (currentEp != null && episodeLinks.isEmpty()) {
            val s = currentEp.groupValues[1].toIntOrNull()
            val e = currentEp.groupValues[2].toIntOrNull()
            episodeLinks[url] = newEpisode(url) { season = s; episode = e }
        }

        return if (episodeLinks.isNotEmpty() || url.contains("/dizi", true) || currentEp != null) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeLinks.values.toList()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SINEFY", "LOAD_LINKS detail=$data")
        val detail = runCatching { app.get(data, headers = headers()) }.getOrElse {
            Log.e("SINEFY", "DETAIL_FAIL ${it.message}")
            return false
        }

        val iframe = detail.document.select("iframe[src]")
            .map { fixUrl(it.attr("src")) }
            .firstOrNull { it.contains("pichive", true) }
            ?: detail.document.selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let(::fixUrl)
            ?: run {
                Log.e("SINEFY", "IFRAME_NOT_FOUND")
                return false
            }

        Log.d("SINEFY", "IFRAME host=${runCatching { URI(iframe).host }.getOrNull()}")
        val frameUri = runCatching { URI(iframe) }.getOrNull() ?: return false
        val origin = "${frameUri.scheme}://${frameUri.host}"
        val v = Regex("""(?:[?&])v=([^&#]+)""").find(iframe)?.groupValues?.getOrNull(1)

        val frameHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val frame = runCatching { app.get(iframe, referer = data, headers = frameHeaders) }.getOrNull()

        val candidates = LinkedHashSet<String>()
        frame?.text?.let { collectUrls(it, candidates) }

        if (!v.isNullOrBlank()) {
            val source2 = "$origin/source2.php?v=${URLEncoder.encode(v, "UTF-8")}" 
            val apiHeaders = mapOf(
                "User-Agent" to ua,
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to origin
            )
            val sourceResponse = runCatching { app.get(source2, referer = iframe, headers = apiHeaders) }.getOrNull()
            if (sourceResponse != null) {
                Log.d("SINEFY", "SOURCE2 status=${sourceResponse.code} chars=${sourceResponse.text.length}")
                collectUrls(sourceResponse.text, candidates)
            }
        }

        val media = candidates.filter { it.startsWith("http") && (it.contains(".m3u8", true) || it.contains("m.php?", true)) }
            .distinct()

        media.forEach { url ->
            callback(newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                this.referer = iframe
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to iframe, "Origin" to origin)
            })
        }

        if (media.isNotEmpty()) return true

        // Bilinen extractor varsa iframe'i ona bırak; URL tahmini yapmıyoruz.
        val before = candidates.size
        runCatching { loadExtractor(iframe, data, subtitleCallback, callback) }
        Log.d("SINEFY", "EXTRACTOR_FALLBACK iframe=$iframe candidates=$before")
        return true
    }

    private fun collectUrls(text: String, out: MutableSet<String>) {
        runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                val root: Any = if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
                walkJson(root, out)
            }
        }
        Regex("""https?://[^\\s'\"<>\\]+""").findAll(text).forEach { m ->
            out += m.value.replace("\\/", "/").trimEnd(')', ']', '}', ',')
        }
    }

    private fun walkJson(value: Any?, out: MutableSet<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> walkJson(value.opt(key), out) }
            is JSONArray -> for (i in 0 until value.length()) walkJson(value.opt(i), out)
            is String -> if (value.startsWith("http://") || value.startsWith("https://")) out += value.replace("\\/", "/")
        }
    }
}
