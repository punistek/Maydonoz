package com.pars.yabancidizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder

class YabanciDizi : MainAPI() {
    override var mainUrl = "https://yabancidizi.news"
    override var name = "YabancıDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/dizi-izle-hd" to "Diziler",
        "$mainUrl/film-izle-hd" to "Filmler",
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return when {
            base.endsWith("/dizi-izle-hd") -> base
            else -> "$base/$page"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = commonHeaders, referer = "$mainUrl/").document

        if (request.data.endsWith("/dizi-izle-hd")) {
            // Site bütün dizi arşivini tek HTML'de veriyor. Uygulamaya 4-5 bin kartı
            // tek seferde göndermek yerine sanal sayfalama yapıyoruz.
            val pageSize = 60
            val roots = doc.select("#page-series_list ul.new-tvseries li.segment-poster-sm")
            val start = ((page.coerceAtLeast(1) - 1) * pageSize).coerceAtMost(roots.size)
            val end = (start + pageSize).coerceAtMost(roots.size)
            val items = parseSeriesArchive(roots.subList(start, end))
            return newHomePageResponse(request.name, items, hasNext = end < roots.size)
        }

        val items = when {
            request.data.endsWith("/kesfet") -> parseDiscover(doc)
            request.data.endsWith("/film-izle-hd") -> parseMovies(doc)
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun parseDiscover(doc: Document): List<SearchResponse> {
        val out = linkedMapOf<String, SearchResponse>()
        doc.select("#discover-response ul.filter-results > li .poster-with-subject").forEach { root ->
            val a = root.selectFirst("a[href^='film/'], a[href^='dizi/']") ?: return@forEach
            val href = a.attr("href").trim()
            val url = absoluteUrl(href)
            val isMovie = href.startsWith("film/")
            val title = root.selectFirst(".subject-title h2, h2.truncate")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("title").removeSuffix(" izle").trim().takeIf { it.isNotBlank() }
                ?: return@forEach
            val poster = root.selectFirst("img")?.let(::imageUrl)

            val response = if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            }
            out[url] = response
        }
        return out.values.toList()
    }

    private fun parseSeriesArchive(roots: List<org.jsoup.nodes.Element>): List<SearchResponse> {
        val out = linkedMapOf<String, SearchResponse>()
        roots.forEach { root ->
            val a = root.selectFirst(".poster a[href^='dizi/']") ?: return@forEach
            val url = absoluteUrl(a.attr("href"))
            val title = root.selectFirst(".poster-subject h2")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("title").removeSuffix(" izle").trim().takeIf { it.isNotBlank() }
                ?: return@forEach
            val poster = root.selectFirst("img")?.let(::imageUrl)
            out[url] = newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        }
        return out.values.toList()
    }

    private fun parseMovies(doc: Document): List<SearchResponse> {
        val out = linkedMapOf<String, SearchResponse>()
        doc.select("#page-movies_list li.mofy-moviesli, li.mofy-moviesli").forEach { root ->
            val a = root.selectFirst(".mofy-movbox-image a[href^='film/'], a[href^='film/']") ?: return@forEach
            val url = absoluteUrl(a.attr("href"))
            val title = root.selectFirst(".mofy-movbox-text a[href^='film/']")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("title").removeSuffix(" izle").trim().takeIf { it.isNotBlank() }
                ?: root.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val poster = root.selectFirst("img")?.let(::imageUrl)
            out[url] = newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = commonHeaders, referer = "$mainUrl/").document
        return if (url.contains("/film/")) movieLoad(url, doc) else seriesLoad(url, doc)
    }

    private suspend fun movieLoad(url: String, doc: Document): LoadResponse {
        val title = cleanTitle(
            og(doc, "og:title")
                ?: doc.selectFirst("h1")?.text()
                ?: "Film"
        )
        val poster = og(doc, "og:image")?.let(::absoluteUrl) ?: doc.selectFirst(".poster img, .series-cover img, .movie-cover img")?.let(::imageUrl)
        val plot = metaDescription(doc) ?: doc.selectFirst(".overview, .description, .excerpt")?.text()?.trim()
        val year = Regex("(?:19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun seriesLoad(url: String, doc: Document): LoadResponse {
        val title = cleanTitle(
            og(doc, "og:title")
                ?: doc.selectFirst("h1")?.text()
                ?: "Dizi"
        )
        val poster = og(doc, "og:image")?.let(::absoluteUrl) ?: doc.selectFirst(".poster img, .series-cover img")?.let(::imageUrl)
        val plot = metaDescription(doc) ?: doc.selectFirst(".overview, .description, .excerpt")?.text()?.trim()

        val episodeMap = linkedMapOf<String, Episode>()
        val episodeRegex = Regex("/sezon-(\\d+)/bolum-(\\d+)(?:$|[/?#])", RegexOption.IGNORE_CASE)

        doc.select("a[href*='/sezon-'][href*='/bolum-']").forEach { a ->
            val epUrl = absoluteUrl(a.attr("href"))
            val m = episodeRegex.find(epUrl) ?: return@forEach
            val seasonNo = m.groupValues[1].toIntOrNull()
            val episodeNo = m.groupValues[2].toIntOrNull()
            val text = a.text().trim()
            val epName = text
                .replace(Regex("^Bölüm\\s+\\d+\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: "${episodeNo ?: "?"}. Bölüm"

            episodeMap[epUrl] = newEpisode(epUrl) {
                name = epName
                season = seasonNo
                episode = episodeNo
            }
        }

        if (episodeMap.isEmpty()) {
            val firstEpisode = "$url/sezon-1/bolum-1"
            runCatching {
                val epResponse = app.get(firstEpisode, headers = commonHeaders, referer = url)
                val epDoc = epResponse.document

                // İlk bölüm sayfası gerçekten oynatılabilir bir bölümse, sayfada bölüm
                // navigasyonu olmasa bile bölümü listeye ekle.
                if (!extractEId(epDoc, epResponse.text).isNullOrBlank()) {
                    episodeMap[firstEpisode] = newEpisode(firstEpisode) {
                        name = "1. Bölüm"
                        season = 1
                        episode = 1
                    }
                }

                epDoc.select("a[href*='/sezon-'][href*='/bolum-']").forEach { a ->
                    val epUrl = absoluteUrl(a.attr("href"))
                    val m = episodeRegex.find(epUrl) ?: return@forEach
                    val seasonNo = m.groupValues[1].toIntOrNull()
                    val episodeNo = m.groupValues[2].toIntOrNull()
                    val text = a.text().trim()
                    val epName = text
                        .replace(Regex("^Bölüm\\s+\\d+\\s*", RegexOption.IGNORE_CASE), "")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: "${episodeNo ?: "?"}. Bölüm"
                    episodeMap[epUrl] = newEpisode(epUrl) {
                        name = epName
                        season = seasonNo
                        episode = episodeNo
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeMap.values.toList()) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[YABANCIDIZI] WEBVIEW_HANDOFF data=$data")

        /*
         * Android NiceHttp /ajax/service isteği bu kaynakta Cloudflare 520
         * döndürüyor. PC Resolver Lab ise aynı detail sayfasını gerçek Chromium
         * contextinde açınca şu zinciri başarıyla görüyor:
         * detail -> /ajax/service -> /api/drives -> ydf.popcornvakti.net
         * -> film.popcornvakti.net/.../q/1 -> #EXTM3U.
         *
         * Baba Burda'nın mevcut universal resolver'ı X-PARS-WEBVIEW marker'ını
         * zaten destekliyor. Bu yüzden extractor burada sahte/yarım HLS üretmez;
         * gerçek detail URL'yi browser runtime'a devreder.
         */
        callback(
            newExtractorLink(
                source = name,
                name = "$name Browser",
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                referer = data
                quality = Qualities.Unknown.value
                headers = commonHeaders + mapOf(
                    "Referer" to data,
                    "X-PARS-WEBVIEW" to "1",
                    "X-PARS-DETAIL-REFERER" to data
                )
            }
        )

        return true
    }

    private fun extractEId(doc: Document, html: String): String? {
        val attrNames = listOf("e_id", "data-e_id", "data-e-id", "data-eid")
        for (attr in attrNames) {
            doc.select("[$attr]").forEach { el ->
                val value = decodeFormValue(el.attr(attr))
                if (!value.isNullOrBlank()) return value
            }
        }

        val patterns = listOf(
            Regex("""[\"']?e_id[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""e_id=([^&\"'\\\s<>]+)""", RegexOption.IGNORE_CASE)
        )
        for (re in patterns) {
            val raw = re.find(html)?.groupValues?.getOrNull(1) ?: continue
            val value = decodeFormValue(raw)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun decodeFormValue(value: String): String? {
        val cleaned = value.trim().replace("&amp;", "&")
        if (cleaned.isBlank()) return null
        return runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    }

    private fun imageUrl(img: org.jsoup.nodes.Element): String? {
        val raw = img.attr("data-src").trim().ifBlank { img.attr("src").trim() }
        if (raw.isBlank() || raw.startsWith("data:")) return null
        return absoluteUrl(raw)
    }

    private fun absoluteUrl(raw: String): String {
        val value = raw.trim().replace("&amp;", "&")
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/${value.trimStart('/')}"
        }
    }

    private fun og(doc: Document, property: String): String? =
        doc.selectFirst("meta[property='$property']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun metaDescription(doc: Document): String? =
        doc.selectFirst("meta[name='description']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+[İi]zle.*$"), "")
        .replace(Regex("\\s*[-|]\\s*yabancidizi.*$", RegexOption.IGNORE_CASE), "")
        .trim()
}
