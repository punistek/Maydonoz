package com.pars.sinefilmizlesen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class SineFilmizlesen : MainAPI() {
    override var mainUrl = "https://www.sinefilmizlesen.cc"
    override var name = "SineFilmizlesen"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/hint-erotik" to "Hint Erotik",
        "$mainUrl/japon-erotik" to "Japon Erotik",
        "$mainUrl/konulu-erotik" to "Konulu Erotik",
        "$mainUrl/yerli-erotik" to "Yerli Erotik",
        "$mainUrl/yesilcam-erotik" to "Yeşilçam Erotik",
        "$mainUrl/Yetiskin-filmleri" to "Yetişkin Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Verilen kaynakta kategori pagination rotası doğrulanmadığı için uydurma URL üretmiyoruz.
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data, referer = "$mainUrl/").document
        val items = doc.select("#icerik .film-k.kutu-icerik, .film-k.kutu-icerik")
            .mapNotNull { it.toMovieSearch() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toMovieSearch(): SearchResponse? {
        val a = selectFirst(".play a[href]") ?: selectFirst("a[href$='.html']") ?: return null
        val href = a.attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val title = selectFirst(".bilgi .baslik")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: a.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst(".resim img")?.attr("src")
            ?.trim()?.takeIf { it.isNotBlank() }?.let(::fixUrlNull)
        val year = Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        // Sayfa kaynağında doğrulanan form:
        // POST /arama/  field=query
        val doc = app.post(
            "$mainUrl/arama/",
            data = mapOf("query" to q, "submit" to "Ara"),
            referer = "$mainUrl/"
        ).document

        return doc.select("#icerik .film-k.kutu-icerik, .film-k.kutu-icerik")
            .mapNotNull { it.toMovieSearch() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document

        val title = doc.selectFirst(".izle-ust .slayt-orta h1 a[href*='sinefilmizlesen.cc']")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - sinefilmizlesen.cc")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let(::fixUrlNull)
            ?: doc.selectFirst(".izle-ust .slayt-poster img")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let(::fixUrlNull)

        val plot = doc.selectFirst(".izle-ust .slayt-aciklama")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = doc.selectFirst(".izle-ust a[href*='-yili-filmleri']")
            ?.text()?.trim()?.toIntOrNull()
            ?: Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()

        val tags = doc.select(".izle-ust .slayt-ust.alt a[href]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val score = doc.select(".izle-ust .slayt-bilgi p").firstOrNull {
            it.text().contains("İMDB", ignoreCase = true)
        }?.selectFirst("b")?.text()?.let { Score.from10(it) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val frames = doc.select("#video iframe[src], iframe[src]")
            .map { fixUrl(it.attr("src")) }
            .filter { it.startsWith("http") }
            .distinct()

        var emitted = false
        for (embed in frames) {
            if (embed.contains("playerzz.xyz/play.php")) {
                if (resolvePlayerZz(embed, callback)) emitted = true
            }

            if (!emitted) {
                loadExtractor(embed, data, subtitleCallback) {
                    emitted = true
                    callback(it)
                }
            }
        }
        return emitted
    }

    private suspend fun resolvePlayerZz(
        embed: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex("""[?&]vid=([^&]+)""").find(embed)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() } ?: return false

        // Resolver Lab kaydında playerzz'nin gerçek runtime isteği:
        // POST /ajax_sources.php
        // vid=<id>&alternative=mp4&ord=0
        val response = runCatching {
            app.post(
                "https://playerzz.xyz/ajax_sources.php",
                data = mapOf(
                    "vid" to vid,
                    "alternative" to "mp4",
                    "ord" to "0"
                ),
                headers = mapOf(
                    "Origin" to "https://playerzz.xyz",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = embed
            ).text
        }.getOrNull() ?: return false

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return false
        if (!json.optString("status").equals("true", ignoreCase = true)) return false

        val sources = json.optJSONArray("source") ?: return false
        var found = false

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val file = source.optString("file").replace("\\/", "/").trim()
            if (!file.startsWith("http")) continue

            val type = source.optString("type").lowercase()
            val isHls = file.contains(".m3u8", ignoreCase = true) ||
                type.contains("mpegurl") || type.contains("m3u8")

            callback(
                newExtractorLink(
                    source = "PlayerZz",
                    name = source.optString("label").ifBlank { "PlayerZz" },
                    url = file,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = "https://playerzz.xyz/"
                    quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }
}
