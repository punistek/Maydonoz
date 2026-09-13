package com.pars.dizimakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class DiziMakinesi : MainAPI() {
    override var mainUrl = "https://dizimakinesi.org"
    override var name = "Dizi Makinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?sort=newest" to "Yeni Diziler",
        "$mainUrl/filmler?sort=newest" to "Yeni Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/kategori/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base + if (base.contains("?")) "&page=$page" else "?page=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = parseItemList(doc)
        return newHomePageResponse(request.name, items)
    }

    private fun jsonObjects(doc: Document): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        doc.select("script[type='application/ld+json']").forEach { script ->
            val text = script.data().ifBlank { script.html() }.trim()
            if (text.isBlank()) return@forEach
            runCatching {
                when (val root = org.json.JSONTokener(text).nextValue()) {
                    is JSONObject -> {
                        out += root
                        val graph = root.optJSONArray("@graph")
                        if (graph != null) for (i in 0 until graph.length()) {
                            graph.optJSONObject(i)?.let(out::add)
                        }
                    }
                    is JSONArray -> for (i in 0 until root.length()) root.optJSONObject(i)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun parseItemList(doc: Document): List<SearchResponse> {
        val list = jsonObjects(doc).firstOrNull { it.optString("@type") == "ItemList" }
            ?.optJSONArray("itemListElement") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i)?.optJSONObject("item") ?: continue
            val title = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
            val url = item.optString("url").trim().takeIf { it.isNotBlank() } ?: continue
            val poster = item.optString("image").trim().takeIf { it.isNotBlank() }
            when (item.optString("@type")) {
                "Movie" -> out += newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
                else -> out += newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            }
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf("$mainUrl/diziler?q=$encoded", "$mainUrl/filmler?q=$encoded")
        val result = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            runCatching { app.get(url, referer = "$mainUrl/").document }
                .getOrNull()?.let { doc -> parseItemList(doc).forEach { result[it.url] = it } }
        }
        return result.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document
        val objects = jsonObjects(doc)
        val movie = objects.firstOrNull { it.optString("@type") == "Movie" }
        if (movie != null) return movieLoad(url, doc, movie)
        val series = objects.firstOrNull { it.optString("@type") == "TVSeries" }
        if (series != null) return seriesLoad(url, doc, series)
        val episode = objects.firstOrNull { it.optString("@type") == "TVEpisode" }
        if (episode != null) {
            val title = episode.optString("name").ifBlank { og(doc, "og:title") ?: "Dizi Makinesi" }
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = episode.optString("image").takeIf { it.isNotBlank() } ?: og(doc, "og:image")
                plot = episode.optString("description").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private suspend fun movieLoad(url: String, doc: Document, o: JSONObject): LoadResponse {
        val title = o.optString("name").ifBlank { og(doc, "og:title") ?: "Film" }
        val poster = o.optString("image").takeIf { it.isNotBlank() } ?: og(doc, "og:image")
        val plot = o.optString("description").takeIf { it.isNotBlank() }
        val year = Regex("(?:19|20)\\d{2}").find(o.optString("datePublished"))?.value?.toIntOrNull()
        val score = o.optJSONObject("aggregateRating")?.optDouble("ratingValue", Double.NaN)
            ?.takeUnless { it.isNaN() }?.let { Score.from10(it) }
        val tags = jsonStrings(o.optJSONArray("genre"))
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
        }
    }

    private suspend fun seriesLoad(url: String, doc: Document, o: JSONObject): LoadResponse {
        val title = o.optString("name").ifBlank { og(doc, "og:title") ?: "Dizi" }
        val poster = o.optString("image").takeIf { it.isNotBlank() } ?: og(doc, "og:image")
        val plot = o.optString("description").takeIf { it.isNotBlank() }
        val score = o.optJSONObject("aggregateRating")?.optDouble("ratingValue", Double.NaN)
            ?.takeUnless { it.isNaN() }?.let { Score.from10(it) }
        val tags = jsonStrings(o.optJSONArray("genre"))
        val episodes = linkedMapOf<String, Episode>()
        val re = Regex("-(\\d+)-sezon-(\\d+)-bolum(?:$|[/?#])", RegexOption.IGNORE_CASE)

        doc.select("a[href*='-sezon-'][href*='-bolum']").forEach { a ->
            val epUrl = fixUrl(a.attr("href"))
            val m = re.find(epUrl) ?: return@forEach
            val seasonNo = m.groupValues[1].toIntOrNull()
            val episodeNo = m.groupValues[2].toIntOrNull()
            val epName = a.selectFirst("h3, h4, .title, .episode-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
                ?: "${episodeNo ?: "?"}. Bölüm"
            episodes[epUrl] = newEpisode(epUrl) {
                name = epName
                season = seasonNo
                episode = episodeNo
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            posterUrl = poster
            this.plot = plot
            this.score = score
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val iframe = doc.selectFirst("iframe[src*='saranmedialive.xyz/video/']")?.attr("src")
            ?.let(::fixUrl) ?: return false
        val hash = Regex("/video/([A-Za-z0-9]+)").find(iframe)?.groupValues?.getOrNull(1) ?: return false
        val playerOrigin = "https://saranmedialive.xyz"
        val apiUrl = "$playerOrigin/player/index.php?data=$hash&do=getVideo"

        val response = app.post(
            apiUrl,
            data = mapOf("hash" to hash, "r" to "$mainUrl/"),
            headers = mapOf(
                "Origin" to playerOrigin,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            ),
            referer = iframe
        )
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: return false
        val secured = json.optString("securedLink").replace("\\/", "/").trim()
        val source = json.optString("videoSource").replace("\\/", "/").trim()
        val finalUrl = secured.takeIf { it.startsWith("http") }
            ?: source.takeIf { it.startsWith("http") }
            ?: return false

        callback(
            newExtractorLink(
                source = "Dizi Makinesi",
                name = "Dizi Makinesi",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = iframe
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "Origin" to playerOrigin,
                    "Referer" to iframe
                )
            }
        )
        return true
    }

    private fun og(doc: Document, property: String): String? =
        doc.selectFirst("meta[property='$property']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun jsonStrings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotBlank) }
    }
}
