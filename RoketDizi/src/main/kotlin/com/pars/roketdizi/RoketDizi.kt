package com.pars.roketdizi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class RoketDizi : MainAPI() {
    override var mainUrl = "https://roketdizi.life"
    override var name = "RoketDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor by lazy { RoketCloudflareInterceptor(cloudflareKiller) }

    class RoketCloudflareInterceptor(
        private val killer: CloudflareKiller
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = runCatching { response.peekBody(1024 * 1024).string() }.getOrDefault("")
            val server = response.header("server").orEmpty()
            val cfRay = response.header("cf-ray").orEmpty()
            val protected = response.code in setOf(403, 429, 503) &&
                (server.contains("cloudflare", true) || cfRay.isNotBlank() ||
                    body.contains("Just a moment", true) ||
                    body.contains("Checking your browser", true) ||
                    body.contains("challenge-platform", true) ||
                    body.contains("cf-chl", true))

            return if (protected || body.contains("Just a moment", true)) {
                response.close()
                killer.intercept(chain)
            } else response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle" to "Filmler",
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/trend" to "Trend"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val sep = if (base.contains('?')) '&' else '?'
        return "$base${sep}page=$page"
    }

    private suspend fun getDoc(url: String, referer: String = "$mainUrl/"): Document =
        app.get(url, referer = referer, interceptor = cfInterceptor).document

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.i("ROKET", "MAIN section=${request.name} page=$page url=$url")
        val doc = getDoc(url)
        val items = parseCards(doc, request.name == "Filmler", request.name == "Diziler")
        Log.i("ROKET", "MAIN DONE section=${request.name} items=${items.size}")
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun parseCards(doc: Document, moviesOnly: Boolean, seriesOnly: Boolean): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()

        doc.select("a[href]").forEach { a ->
            val raw = a.attr("href").trim()
            val isMovie = raw.contains("/film/") && raw.contains("/izle")
            val isSeries = raw.contains("/dizi/") && !raw.contains("/sezon-") && !raw.contains("/bolum-")
            if (!isMovie && !isSeries) return@forEach
            if (moviesOnly && !isMovie) return@forEach
            if (seriesOnly && !isSeries) return@forEach

            val href = fixUrlNull(raw) ?: return@forEach
            if (!seen.add(href)) return@forEach

            val img = a.selectFirst("img")
            val title = listOf(
                a.attr("title"),
                img?.attr("alt").orEmpty(),
                a.selectFirst("h1,h2,h3,h4,.title,.name")?.text().orEmpty(),
                a.text()
            ).firstOrNull { it.trim().length >= 2 }?.trim()?.replace(Regex("\\s+"), " ")
                ?: href.substringAfterLast('/').replace('-', ' ')

            val poster = img?.let(::posterFrom)
            val year = Regex("(?:19|20)\\d{2}").find(a.text())?.value?.toIntOrNull()

            if (isMovie) {
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    this.year = year
                }
            } else {
                out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    this.year = year
                }
            }
        }
        return out
    }

    private fun posterFrom(img: Element): String? {
        val raw = listOf("src", "data-src", "data-lazy-src", "data-original")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("srcset").split(',').firstOrNull()?.trim()?.substringBefore(' ')
        return raw?.takeIf { it.isNotBlank() }?.let(::fixUrlNull)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().length < 2) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf("$mainUrl/s?q=$q", "$mainUrl/ara?q=$q")
        for (url in urls) {
            val items = runCatching { parseCards(getDoc(url), false, false) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i("ROKET", "LOAD $url")
        val doc = getDoc(url)
        val jsonLd = jsonLdObjects(doc)

        val movie = jsonLd.firstOrNull { it.optString("@type").equals("Movie", true) }
        if (movie != null) return movieLoad(url, doc, movie)

        val series = jsonLd.firstOrNull { it.optString("@type").equals("TVSeries", true) }
        if (series != null) return seriesLoad(url, doc, series)

        // Tek bölüm URL'si doğrudan açılırsa da oynatılabilir bir episode response üret.
        val episode = jsonLd.firstOrNull { it.optString("@type").equals("TVEpisode", true) }
        if (episode != null) {
            val title = episode.optString("name").ifBlank { og(doc, "og:title") ?: "RoketDizi" }
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = episode.optString("image").takeIf { it.isNotBlank() }
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
        val actors = jsonArray(o.opt("actor")).mapNotNull { (it as? JSONObject)?.optString("name")?.takeIf(String::isNotBlank) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            addActors(actors)
        }
    }

    private suspend fun seriesLoad(url: String, doc: Document, o: JSONObject): LoadResponse {
        val title = o.optString("name").ifBlank { og(doc, "og:title") ?: "Dizi" }
        val poster = o.optString("image").takeIf { it.isNotBlank() } ?: og(doc, "og:image")
        val plot = o.optString("description").takeIf { it.isNotBlank() }
        val score = o.optJSONObject("aggregateRating")?.optDouble("ratingValue", Double.NaN)
            ?.takeUnless { it.isNaN() }?.let { Score.from10(it) }
        val actors = jsonArray(o.opt("actor")).mapNotNull { (it as? JSONObject)?.optString("name")?.takeIf(String::isNotBlank) }
        val episodes = mutableListOf<Episode>()

        jsonArray(o.opt("containsSeason")).forEach { rawSeason ->
            val seasonObj = rawSeason as? JSONObject ?: return@forEach
            val sn = seasonObj.optInt("seasonNumber", 0).takeIf { it > 0 }
            jsonArray(seasonObj.opt("episode")).forEach { rawEp ->
                val ep = rawEp as? JSONObject ?: return@forEach
                val epUrl = ep.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
                val en = ep.optInt("episodeNumber", 0).takeIf { it > 0 }
                episodes += newEpisode(epUrl) {
                    name = ep.optString("name").takeIf { it.isNotBlank() }
                    this.season = sn
                    this.episode = en
                    description = ep.optString("description").takeIf { it.isNotBlank() }
                }
            }
        }

        Log.i("ROKET", "SERIES title=$title episodes=${episodes.size}")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.score = score
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("ROKET", "LINKS START detail=$data")
        val response = runCatching {
            app.get(data, referer = "$mainUrl/", interceptor = cfInterceptor)
        }.getOrElse {
            Log.e("ROKET", "DETAIL FAIL ${it.message}")
            return false
        }

        val embeds = linkedSetOf<String>()
        response.document.select("iframe[src],iframe[data-src]").forEach { frame ->
            val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
            fixUrlNull(raw)?.let { embeds += it }
        }

        // Next/React kaynaklarında URL string olarak gömülmüş iframe/player adreslerini de topla.
        Regex("""https?:\\?/\\?/[^\"'<>\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(response.text).forEach { m ->
                val u = m.value.replace("\\/", "/").replace("&amp;", "&")
                if (u.contains("iframe", true) || u.contains("pichive", true)) embeds += u
            }

        Log.i("ROKET", "EMBEDS count=${embeds.size} hosts=${embeds.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }.distinct()}")

        var emitted = 0
        for (embed in embeds) {
            runCatching {
                // Önce Cloudflare oturumunu iframe hostunda kurmayı dene; ardından CloudStream extractor zincirine ver.
                app.get(embed, referer = data, interceptor = cfInterceptor)
                loadExtractor(embed, data, subtitleCallback) { link ->
                    emitted++
                    Log.i("ROKET", "EMIT type=${link.type} quality=${link.quality} host=${runCatching { java.net.URI(link.url).host }.getOrNull()}")
                    callback(link)
                }
            }.onFailure {
                Log.e("ROKET", "EMBED FAIL host=${runCatching { java.net.URI(embed).host }.getOrNull()} ${it::class.simpleName}: ${it.message}")
            }
        }

        if (emitted == 0) {
            Log.w("ROKET", "NO_LINK runtime player may require browser/user-gesture path; no fake/static master emitted")
        }
        return emitted > 0
    }

    private fun jsonLdObjects(doc: Document): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        doc.select("script[type=application/ld+json]").forEach { script ->
            val text = script.data().ifBlank { script.html() }.trim()
            if (text.isBlank()) return@forEach
            runCatching {
                when {
                    text.startsWith("[") -> {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) (arr.opt(i) as? JSONObject)?.let(out::add)
                    }
                    else -> {
                        val obj = JSONObject(text)
                        out += obj
                        val graph = obj.optJSONArray("@graph")
                        if (graph != null) for (i in 0 until graph.length()) (graph.opt(i) as? JSONObject)?.let(out::add)
                    }
                }
            }
        }
        return out
    }

    private fun jsonArray(value: Any?): List<Any> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    private fun og(doc: Document, property: String): String? =
        doc.selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
}
