package com.pars.dmax

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

class DMAX : MainAPI() {
    override var mainUrl = "https://www.dmax.com.tr"
    override var name = "DMAX"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/kesfet/dogayla-ic-ice" to "Doğayla İç İçe",
        "$mainUrl/kesfet/zorlu-isler" to "Zorlu İşler",
        "$mainUrl/kesfet/belgesel" to "Belgesel",
        "$mainUrl/kesfet/nasil-yapiliyor" to "Nasıl Yapılıyor",
    )

    private val commonHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val firstResponse = app.get(base, headers = commonHeaders, referer = "$mainUrl/")
        val firstDoc = firstResponse.document

        val items = if (page <= 1) {
            parseProgramPosters(firstDoc)
        } else {
            val slug = base.substringAfter("/kesfet/", "").substringBefore('?').trim('/')
            val csrf = firstDoc.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()
            val text = postMoreDiscover(slug, page, csrf, base)
            parseProgramPosters(Jsoup.parse(extractAjaxHtml(text), mainUrl))
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val searchPage = app.get("$mainUrl/kesfet", headers = commonHeaders, referer = "$mainUrl/")
        val csrf = searchPage.document.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()

        val response = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("query" to q),
            headers = ajaxHeaders(csrf),
            referer = "$mainUrl/kesfet",
        )

        val html = extractAjaxHtml(response.text)
        val parsed = parseProgramPosters(Jsoup.parse(html, mainUrl))
        if (parsed.isNotEmpty()) return parsed

        // Yedek: normal arama sayfası.
        val encoded = URLEncoder.encode(q, "UTF-8")
        val doc = app.get(
            "$mainUrl/arama?sorgu=$encoded",
            headers = commonHeaders,
            referer = "$mainUrl/",
        ).document
        return parseProgramPosters(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = commonHeaders, referer = "$mainUrl/")
        val doc = response.document

        // Bir bölüm URL'si doğrudan açılırsa tek bölümlük dizi cevabı oluştur.
        val urlEpisode = parseSeasonEpisode(url)
        if (urlEpisode != null) {
            val programTitle = cleanProgramTitle(
                doc.selectFirst("h1")?.text()
                    ?: og(doc, "og:title")
                    ?: slugTitle(url.substringAfter(mainUrl).substringBefore('/'))
            )
            val ep = newEpisode(url) {
                name = episodeDisplayName(doc, urlEpisode.first, urlEpisode.second)
                season = urlEpisode.first
                episode = urlEpisode.second
                posterUrl = og(doc, "og:image")
            }
            return newTvSeriesLoadResponse(programTitle, url, TvType.TvSeries, listOf(ep)) {
                posterUrl = og(doc, "og:image")
                plot = metaDescription(doc)
            }
        }

        val title = cleanProgramTitle(
            doc.selectFirst("h1")?.text()
                ?: og(doc, "og:title")
                ?: doc.title()
                ?: slugTitle(url.substringAfterLast('/'))
        )
        val poster = firstImage(doc)
        val plot = programDescription(doc)
        val episodes = collectEpisodes(url, doc)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun collectEpisodes(programUrl: String, doc: Document): List<Episode> {
        val out = linkedMapOf<String, Episode>()
        addEpisodesFromDoc(doc, out)

        val container = doc.selectFirst(".dyn-content.program-episodes")
        val programId = container?.attr("data-program-id")?.trim().orEmpty()
        val csrf = doc.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()

        val seasons = linkedSetOf<String>()
        doc.select("#video-filter-changer option[value], select option[value]").forEach { option ->
            val v = option.attr("value").trim()
            if (v.matches(Regex("\\d+"))) seasons += v
        }

        // Sayfada sezon seçici yoksa görünen bölümlerin sezonlarını kullan.
        if (seasons.isEmpty()) {
            out.values.mapNotNull { it.season?.toString() }.forEach(seasons::add)
        }

        if (programId.isNotBlank()) {
            for (season in seasons) {
                var page = 0
                var emptyRounds = 0
                while (page < 25 && emptyRounds < 1) {
                    val res = app.post(
                        "$mainUrl/ajax/more",
                        data = mapOf(
                            "type" to "episodes",
                            "program_id" to programId,
                            "page" to page.toString(),
                            "season" to season,
                        ),
                        headers = ajaxHeaders(csrf),
                        referer = programUrl,
                    )
                    val before = out.size
                    addEpisodesFromDoc(Jsoup.parse(extractAjaxHtml(res.text), mainUrl), out)
                    if (out.size == before) emptyRounds++ else emptyRounds = 0
                    page++
                }
            }
        }

        return out.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
    }

    private fun addEpisodesFromDoc(doc: Document, out: MutableMap<String, Episode>) {
        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { fixUrl(a.attr("href")) }
            val se = parseSeasonEpisode(href) ?: return@forEach
            if (!href.startsWith(mainUrl)) return@forEach

            val rawText = a.text().trim()
            val name = rawText.takeIf { it.isNotBlank() }
                ?: "${se.first}. Sezon ${se.second}. Bölüm"
            val image = a.selectFirst("img")?.let { img ->
                img.absUrl("src").ifBlank { fixUrl(img.attr("src")) }
            }?.takeIf { it.isNotBlank() }

            out[href] = newEpisode(href) {
                this.name = name
                season = se.first
                episode = se.second
                posterUrl = image
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = app.get(data, headers = commonHeaders, referer = "$mainUrl/")
        val html = page.text

        // DMAX sayfasindaki gercek video kimligi.
        val referenceId = Regex(
            "player/info\\?referenceId=([A-Za-z0-9_-]+)",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("adm-player-([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: return false

        // DMAX'in kendi media-player bundle.js dosyasi Video.js kaynagini tam olarak
        // bu redirect servisiyle kuruyor. player/info -> flavors.hls kullanilmiyor;
        // o alan geoblock1_smil uyarisini dondurebiliyor.
        val redirectUrl =
            "https://dygvideo.dygdigital.com/api/redirect" +
                "?PublisherId=27" +
                "&ReferenceId=$referenceId" +
                "&SecretKey=NtvApiSecret2014*"

        callback(
            newExtractorLink(
                source = "DMAX",
                name = "DMAX",
                url = redirectUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                referer = data
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "Referer" to data,
                    "Origin" to mainUrl,
                    "User-Agent" to commonHeaders.getValue("User-Agent"),
                )
            }
        )

        return true
    }

    private suspend fun postMoreDiscover(slug: String, page: Int, csrf: String, referer: String): String {
        return app.post(
            "$mainUrl/ajax/more",
            data = mapOf(
                "type" to "discover",
                "slug" to slug,
                "page" to page.toString(),
            ),
            headers = ajaxHeaders(csrf),
            referer = referer,
        ).text
    }

    private fun ajaxHeaders(csrf: String): Map<String, String> {
        val h = linkedMapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to commonHeaders.getValue("User-Agent"),
        )
        if (csrf.isNotBlank()) h["X-CSRF-TOKEN"] = csrf
        return h
    }

    private fun parseProgramPosters(doc: Document): List<SearchResponse> {
        val out = linkedMapOf<String, SearchResponse>()

        // Kritik: Sayfada kategori listesinden önce ortak carousel/poster blokları da var.
        // Tüm `.poster` elemanlarını almak her kategoride aynı ilk içeriklerin görünmesine
        // neden oluyordu. Gerçek kategori sonuçları yalnız `section.grid.dyn-content` içinde.
        var roots = doc.select("section.grid.dyn-content > .poster, section.grid.dyn-content .poster")

        // /ajax/more cevabı sadece poster fragmenti döndürebilir; o durumda güvenli fallback.
        if (roots.isEmpty()) roots = doc.select(".poster")

        roots.forEach { poster ->
            val a = poster.selectFirst("a[href]") ?: return@forEach
            val url = a.absUrl("href").ifBlank { fixUrl(a.attr("href")) }
            if (!url.startsWith(mainUrl)) return@forEach
            if (url.contains("/blog/") || url.contains("/arama") || url.contains("/kisa-video/")) return@forEach

            val slug = url.substringAfter(mainUrl).trim('/').substringBefore('/')
            if (slug.isBlank() || slug in setOf("kesfet", "canli-izle", "yayin-akisi")) return@forEach

            val title = slugTitle(slug)
            val img = poster.selectFirst("img")
            val image = img?.let {
                it.absUrl("src").ifBlank { fixUrl(it.attr("src")) }
            }?.takeIf { it.isNotBlank() }

            out[url] = newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = image
            }
        }

        // AJAX arama cevabı farklı kapta gelse bile program linklerini yakala.
        if (out.isEmpty()) {
            doc.select("a[href]").forEach { a ->
                val url = a.absUrl("href").ifBlank { fixUrl(a.attr("href")) }
                val path = url.substringAfter(mainUrl, "")
                if (!url.startsWith(mainUrl) || path.isBlank()) return@forEach
                if (parseSeasonEpisode(url) != null || path.contains("/kisa-video/") || path.startsWith("/blog/")) return@forEach
                val slug = path.trim('/').substringBefore('/')
                if (slug.isBlank() || slug in setOf("kesfet", "canli-izle", "yayin-akisi", "arama")) return@forEach
                val img = a.selectFirst("img") ?: return@forEach
                val image = img.absUrl("src").ifBlank { fixUrl(img.attr("src")) }.takeIf { it.isNotBlank() }
                out[url] = newTvSeriesSearchResponse(slugTitle(slug), url, TvType.TvSeries) {
                    posterUrl = image
                }
            }
        }

        return out.values.toList()
    }

    private fun extractAjaxHtml(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return text

        return runCatching {
            val root: Any = if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
            findHtml(root)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
    }

    private fun findHtml(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                val preferred = listOf("html", "view", "content", "data", "result")
                for (k in preferred) {
                    if (!value.has(k)) continue
                    val v = value.opt(k)
                    if (v is String && v.contains("<")) return v
                    val nested = findHtml(v)
                    if (!nested.isNullOrBlank()) return nested
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val nested = findHtml(value.opt(keys.next()))
                    if (!nested.isNullOrBlank()) return nested
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                val nested = findHtml(value.opt(i))
                if (!nested.isNullOrBlank()) return nested
            }
            is String -> if (value.contains("<div") || value.contains("<a")) return value
        }
        return null
    }

    private fun parseSeasonEpisode(url: String): Pair<Int, Int>? {
        val m = Regex("/(\\d+)-sezon-(\\d+)-bolum(?:$|[/?#])", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        val season = m.groupValues[1].toIntOrNull() ?: return null
        val episode = m.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    private fun episodeDisplayName(doc: Document, season: Int, episode: Int): String {
        val candidates = listOf(
            doc.selectFirst("h2")?.text(),
            doc.selectFirst(".episode-title")?.text(),
            doc.selectFirst(".video-title")?.text(),
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
            ?: "$season. Sezon $episode. Bölüm"
    }

    private fun firstImage(doc: Document): String? {
        val og = og(doc, "og:image")
        if (!og.isNullOrBlank()) return og
        return doc.selectFirst(".program-detail img, .program-cover img, .poster img, main img")?.let { img ->
            img.absUrl("src").ifBlank { fixUrl(img.attr("src")) }
        }?.takeIf { it.isNotBlank() }
    }

    private fun programDescription(doc: Document): String? {
        val specific = doc.selectFirst(".program-description, .description, .summary, .program-detail p")
            ?.text()?.trim()?.takeIf { it.length > 20 }
        return specific ?: metaDescription(doc)
    }

    private fun metaDescription(doc: Document): String? =
        doc.selectFirst("meta[name='description']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun og(doc: Document, property: String): String? =
        doc.selectFirst("meta[property='$property']")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    private fun cleanProgramTitle(value: String): String {
        return value
            .substringBefore(" - İzle", value)
            .substringBefore(" | DMAX", value)
            .trim()
            .ifBlank { "DMAX" }
    }

    private fun slugTitle(slug: String): String {
        return slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale("tr", "TR")) else c.toString()
                }
            }
    }
}
