package com.pars.dizipal

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Dizipal : MainAPI() {

    override var mainUrl = SEED_BASE
    override var name = "Dizipal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Kategori URL'lerini domain'den bağımsız path olarak tutuyoruz.
    // Domain değişirse bütün kategoriler otomatik yeni domaine taşınır.
    override val mainPage = mainPageOf(
        "/diziler" to "Diziler",
        "/filmler" to "Filmler",
        "/platform/blutv" to "BluTV",
        "/platform/netflix" to "Netflix",
    )

    companion object {
        private const val SEED_BASE = "https://dizipal2130.com"
        private const val STABLE_CANONICAL_HOST = "https://guj-international-brands.com"
        private const val DOMAIN_CACHE_MS = 15L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        @Volatile
        private var cachedBase: String = SEED_BASE

        @Volatile
        private var cachedAt: Long = 0L
    }

    // ---------------------------------------------------------------------
    // DOMAIN DISCOVERY
    // ---------------------------------------------------------------------

    private fun cleanBase(value: String): String =
        value.trim().trimEnd('/')

    private fun normalizePossibleBase(value: String?): String? {
        val raw = value?.trim()?.trimEnd('/') ?: return null
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
        return runCatching {
            val uri = URI(raw)
            val host = uri.host ?: return@runCatching null
            "${uri.scheme}://$host"
        }.getOrNull()
    }

    private fun isDizipalDocument(doc: Document): Boolean {
        val title = doc.title()
        val siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content").orEmpty()
        val bodyText = doc.body()?.text().orEmpty()
        val baseScript = doc.select("script").any {
            it.data().contains("BASE_URL", ignoreCase = true) &&
                it.data().contains("dizipal", ignoreCase = true)
        }

        return title.contains("Dizipal", ignoreCase = true) ||
            siteName.contains("Dizipal", ignoreCase = true) ||
            bodyText.contains("DiziPAL", ignoreCase = true) ||
            baseScript
    }

    private fun extractBaseUrlFromHtml(html: String): String? {
        val patterns = listOf(
            Regex("""const\s+BASE_URL\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:url|baseUrl|base_url)["']\s*:\s*["'](https?://[^"'\\]+)""", RegexOption.IGNORE_CASE),
            Regex("""https?://dizipal\d+\.com""", RegexOption.IGNORE_CASE),
        )

        for (regex in patterns) {
            val value = regex.find(html)?.groupValues?.getOrNull(1)
                ?: regex.find(html)?.value
                ?: continue
            val fixed = value.replace("\\/", "/")
            normalizePossibleBase(fixed)?.let { return it }
        }
        return null
    }

    private suspend fun validateBase(base: String): String? {
        val b = cleanBase(base)
        val response = runCatching {
            app.get(
                "$b/filmler",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 10L
            )
        }.getOrNull() ?: return null

        val doc = response.document
        if (!isDizipalDocument(doc)) return null

        // Sayfanın kendi BASE_URL değeri varsa onu gerçek domain olarak öne al.
        val announced = extractBaseUrlFromHtml(response.text)
        if (announced != null) {
            val announcedResponse = runCatching {
                app.get(
                    "$announced/filmler",
                    headers = mapOf("User-Agent" to USER_AGENT),
                    timeout = 10L
                )
            }.getOrNull()

            if (announcedResponse != null && isDizipalDocument(announcedResponse.document)) {
                return cleanBase(announced)
            }
        }

        val finalBase = normalizePossibleBase(response.url)
        return finalBase ?: b
    }

    private fun numericCandidates(seed: String, radius: Int = 60): List<String> {
        val m = Regex("""^(https?://dizipal)(\d+)(\.com)$""", RegexOption.IGNORE_CASE)
            .find(cleanBase(seed)) ?: return emptyList()

        val prefix = m.groupValues[1]
        val number = m.groupValues[2].toIntOrNull() ?: return emptyList()
        val suffix = m.groupValues[3]

        val out = ArrayList<String>()
        for (delta in 1..radius) {
            out += "$prefix${number + delta}$suffix"
            val lower = number - delta
            if (lower > 0) out += "$prefix$lower$suffix"
        }
        return out
    }

    private suspend fun resolveBase(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && now - cachedAt < DOMAIN_CACHE_MS) {
            return cachedBase
        }

        // 1) Son çalışan domain.
        validateBase(cachedBase)?.let {
            cachedBase = it
            cachedAt = now
            mainUrl = it
            return it
        }

        // 2) Başlangıç domaini.
        if (!cachedBase.equals(SEED_BASE, ignoreCase = true)) {
            validateBase(SEED_BASE)?.let {
                cachedBase = it
                cachedAt = now
                mainUrl = it
                return it
            }
        }

        // 3) Kaynak HTML'de görülen sabit canonical host.
        // Bu host çalışıyorsa sayfadaki BASE_URL'den aktif Dizipal domainini keşfeder.
        validateBase(STABLE_CANONICAL_HOST)?.let {
            cachedBase = it
            cachedAt = now
            mainUrl = it
            return it
        }

        // Canonical host doğrudan Dizipal sayfasını vermese bile HTML içinde
        // güncel BASE_URL / dizipalNNNN.com izi bulunabilir.
        val discoveryHtml = runCatching {
            app.get(
                "$STABLE_CANONICAL_HOST/filmler",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 10L
            ).text
        }.getOrNull()

        extractBaseUrlFromHtml(discoveryHtml.orEmpty())?.let { discovered ->
            validateBase(discovered)?.let {
                cachedBase = it
                cachedAt = now
                mainUrl = it
                return it
            }
        }

        // 4) Son çare: mevcut sayısal domainin yakın komşularını tara.
        // 2130 -> 2131, 2129, 2132, 2128 ... 2190/2070 aralığı.
        val numericSeed = Regex("""https?://dizipal\d+\.com""", RegexOption.IGNORE_CASE)
            .find(cachedBase)?.value ?: SEED_BASE

        for (candidate in numericCandidates(numericSeed, 60)) {
            validateBase(candidate)?.let {
                cachedBase = it
                cachedAt = now
                mainUrl = it
                return it
            }
        }

        // Hiçbiri cevap vermezse son bilinen domaini döndür.
        // Sonraki ağ isteğinde force=true ile tekrar keşif yapılır.
        return cachedBase
    }

    private fun pathOf(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("/")) return value
        return runCatching {
            val uri = URI(value)
            buildString {
                append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
                if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
            }
        }.getOrElse { value }
    }

    private fun currentUrl(raw: String, base: String): String {
        val value = raw.trim().replace("\\/", "/")
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val host = runCatching { URI(value).host.orEmpty() }.getOrDefault("")
            return if (
                host.contains("dizipal", ignoreCase = true) ||
                host.contains("guj-international-brands", ignoreCase = true)
            ) {
                cleanBase(base) + pathOf(value)
            } else value
        }
        return cleanBase(base) + if (value.startsWith("/")) value else "/$value"
    }

    private suspend fun getWithDomain(pathOrUrl: String): Pair<String, NiceResponse> {
        var base = resolveBase()
        var url = currentUrl(pathOrUrl, base)

        var response = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$base/",
                timeout = 15L
            )
        }.getOrNull()

        if (response == null || response.code !in 200..399 || !isDizipalDocument(response.document)) {
            base = resolveBase(force = true)
            url = currentUrl(pathOrUrl, base)
            response = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$base/",
                timeout = 15L
            )
        }

        return base to response
    }

    // ---------------------------------------------------------------------
    // HOME / SEARCH
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = resolveBase()
        val path = pathOf(request.data).substringBefore("?")
        val firstUrl = "$base$path"

        val response = if (page <= 1) {
            app.get(
                firstUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$base/"
            )
        } else {
            // Kaynak sayfadaki InfiniteScroll yapısı:
            // GET <kategori>?page=N&filters=<data-filters>
            val firstPage = app.get(
                firstUrl,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$base/"
            )

            val filters = firstPage.document
                .selectFirst("#contentGrid[data-filters]")
                ?.attr("data-filters")
                ?.takeIf { it.isNotBlank() }

            val pageUrl = buildString {
                append(firstUrl)
                append("?page=").append(page)
                if (!filters.isNullOrBlank()) {
                    append("&filters=")
                    append(URLEncoder.encode(filters, StandardCharsets.UTF_8.toString()))
                }
            }

            app.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json,text/html,*/*"
                ),
                referer = firstUrl
            )
        }

        val doc = documentFromPossibleAjax(response.text, response.document)
        val items = parseCards(doc, base)

        return newHomePageResponse(request.name, items)
    }

    private fun documentFromPossibleAjax(text: String, fallback: Document): Document {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return fallback

        val html = runCatching {
            val root: Any = if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
            findHtmlFragment(root)
        }.getOrNull()

        return if (!html.isNullOrBlank()) Jsoup.parse(html) else fallback
    }

    private fun findHtmlFragment(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys()
                var best: String? = null
                while (keys.hasNext()) {
                    val candidate = findHtmlFragment(value.opt(keys.next()))
                    if (
                        candidate != null &&
                        candidate.contains("content-card", ignoreCase = true) &&
                        (best == null || candidate.length > best.length)
                    ) {
                        best = candidate
                    }
                }
                best
            }
            is JSONArray -> {
                var best: String? = null
                for (i in 0 until value.length()) {
                    val candidate = findHtmlFragment(value.opt(i))
                    if (
                        candidate != null &&
                        candidate.contains("content-card", ignoreCase = true) &&
                        (best == null || candidate.length > best.length)
                    ) {
                        best = candidate
                    }
                }
                best
            }
            is String -> value.takeIf {
                it.contains("content-card", ignoreCase = true) ||
                    it.contains("card-link", ignoreCase = true)
            }
            else -> null
        }
    }

    private fun parseCards(doc: Document, base: String): List<SearchResponse> {
        return doc.select(
            "#contentGrid .content-card, #seriesGrid .content-card, " +
                "#movieGrid .content-card, article.content-card, li.content-card"
        ).mapNotNull { it.toSearchResult(base) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(base: String): SearchResponse? {
        val a = selectFirst("a.card-link[href], a[href*='/film/'], a[href*='/dizi/']")
            ?: return null

        val href = currentUrl(a.attr("href"), base)
        val title = selectFirst(".card-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: a.selectFirst("img[alt]")?.attr("alt")
                ?.replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val image = selectFirst("img")
        val posterRaw = image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }

        val poster = posterRaw
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http") }

        val year = selectFirst(".card-year")?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""(?:19|20)\d{2}""")
                .find(selectFirst(".card-meta")?.text().orEmpty())
                ?.value?.toIntOrNull()

        val typeText = selectFirst(".card-badge.type")?.text().orEmpty()
        val isSeries = href.contains("/dizi/") || typeText.contains("Dizi", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val base = resolveBase()
        val url = "$base/arama?q=" +
            URLEncoder.encode(q, StandardCharsets.UTF_8.toString())

        val response = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$base/"
            )
        }.getOrElse {
            val newBase = resolveBase(force = true)
            app.get(
                "$newBase/arama?q=" +
                    URLEncoder.encode(q, StandardCharsets.UTF_8.toString()),
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$newBase/"
            )
        }

        val liveBase = normalizePossibleBase(response.url) ?: base
        return parseCards(response.document, liveBase)
    }

    // ---------------------------------------------------------------------
    // DETAIL / EPISODES
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val (base, response) = getWithDomain(url)
        val doc = response.document
        val current = currentUrl(url, base)

        val title = doc.selectFirst(".watch-title-top h1, h1")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("| Dizipal")
                ?.replace("Türkçe Dublaj & Altyazı izle", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http") }

        val plot = extractJsonLdDescription(doc)
            ?: doc.selectFirst(".watch-article-body p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = doc.selectFirst(".film-year")?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: extractJsonLdYear(doc)
            ?: Regex("""(?:19|20)\d{2}""")
                .find(doc.selectFirst("meta[name=description]")?.attr("content").orEmpty())
                ?.value?.toIntOrNull()

        val episodeAnchors = doc.select(
            // Dizipal'in güncel dizi detay yapısı (Magarsus vb.)
            ".detail-episode-list a.detail-episode-item[href], " +
                "a.detail-episode-item[href*='/bolum/'], " +
                // Eski/alternatif Dizipal bölüm yapıları
                ".episode-panel a.episode-item[href], " +
                "a.episode-item[href*='/bolum/'], " +
                // Son güvenli fallback
                "a[href*='/bolum/']"
        ).filter { a ->
            a.attr("href").contains("/bolum/", ignoreCase = true)
        }.distinctBy { it.attr("href") }

        val isSeries = current.contains("/dizi/") || episodeAnchors.isNotEmpty()

        if (isSeries) {
            val episodes = episodeAnchors.mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epUrl = currentUrl(href, base)
                val text = a.text().trim()

                val path = pathOf(epUrl)
                val fromPath = Regex(
                    """-(\d+)-sezon-(\d+)-bolum(?:$|[/?#])""",
                    RegexOption.IGNORE_CASE
                ).find(path)

                val season = fromPath?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 1

                val episode = fromPath?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = text.ifBlank {
                        buildString {
                            append(season).append(". Sezon")
                            episode?.let { append(" ").append(it).append(". Bölüm") }
                        }
                    }
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, current, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, current, TvType.Movie, current) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun extractJsonLdDescription(doc: Document): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.data().ifBlank { script.html() }
            val value = Regex(
                """"description"\s*:\s*"((?:\\.|[^"])*)""""
            ).find(text)?.groupValues?.getOrNull(1) ?: continue

            return value
                .replace("\\u0027", "'")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .trim()
                .takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun extractJsonLdYear(doc: Document): Int? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.data().ifBlank { script.html() }
            val value = Regex(
                """"datePublished"\s*:\s*"((?:19|20)\d{2})""""
            ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (value != null) return value
        }
        return null
    }

    // ---------------------------------------------------------------------
    // PLAYER
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (base, response) = getWithDomain(data)
        val detailUrl = currentUrl(data, base)
        var doc = response.document
        var playableUrl = detailUrl

        var container = doc.selectFirst("#videoContainer[data-cfg]")

        // Dizi ana detay URL'si yanlışlıkla doğrudan loadLinks'e gelirse
        // player aramak yerine sayfadaki ilk gerçek bölüm URL'sine geç.
        if (container == null && pathOf(detailUrl).contains("/dizi/")) {
            val firstEpisode = doc.selectFirst(
                ".detail-episode-list a.detail-episode-item[href*='/bolum/'], " +
                    "a.detail-episode-item[href*='/bolum/'], " +
                    "a[href*='/bolum/']"
            )?.attr("href")?.takeIf { it.isNotBlank() }

            if (firstEpisode != null) {
                playableUrl = currentUrl(firstEpisode, base)
                val episodeResponse = runCatching {
                    app.get(
                        playableUrl,
                        headers = mapOf("User-Agent" to USER_AGENT),
                        referer = detailUrl,
                        timeout = 15L
                    )
                }.getOrNull()

                if (episodeResponse != null) {
                    doc = episodeResponse.document
                    container = doc.selectFirst("#videoContainer[data-cfg]")
                }
            }
        }

        container ?: return false
        val cfg = container.attr("data-cfg").trim()
        if (cfg.isBlank()) return false

        val embedCandidates = linkedSetOf<String>()

        // 1) data-cfg Base64 içinden doğrudan gerçek embed.
        decodeCfgEmbed(cfg)?.let { embedCandidates += it }

        // 2) Sitenin kendi /ajax dispatcher akışını da dene.
        resolveAjaxEmbed(
            base = base,
            detailUrl = playableUrl,
            container = container,
            cfg = cfg
        )?.let { embedCandidates += it }

        var emitted = false

        for (embed in embedCandidates) {
            val embedUrl = embed.replace("\\/", "/")
            if (!embedUrl.startsWith("http")) continue

            // FormationFeed/JWPlayer sayfasındaki gerçek HLS doğrudan HTML'de.
            val embedResponse = runCatching {
                app.get(
                    embedUrl,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    referer = playableUrl,
                    timeout = 15L
                )
            }.getOrNull() ?: continue

            val hlsUrls = extractHlsUrls(embedResponse.text)
            val referer = originOf(embedUrl)

            for (hls in hlsUrls) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = hls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer
                        )
                        this.referer = referer
                        quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            }

            if (emitted) return true

            // Tanınmayan başka embed gelirse CloudStream extractorlarını dene.
            loadExtractor(embedUrl, playableUrl, subtitleCallback) {
                emitted = true
                callback(it)
            }

            if (emitted) return true
        }

        return emitted
    }

    private fun decodeCfgEmbed(cfg: String): String? {
        val decoded = runCatching {
            val bytes = Base64.decode(cfg, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return null

        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        return json.optString("v")
            .replace("\\/", "/")
            .trim()
            .takeIf { it.startsWith("http") }
    }

    private suspend fun resolveAjaxEmbed(
        base: String,
        detailUrl: String,
        container: Element,
        cfg: String
    ): String? {
        val type = container.attr("data-content-type").trim()
        val id = container.attr("data-content-id").trim()

        // Resolver Lab'da gözlenen gerçek sıra:
        // GET /ajax-token
        // POST /ajax-view type=<...>&id=<...>&csrf_token=<...>
        // POST /ajax cfg=<base64>
        val token = runCatching {
            val text = app.get(
                "$base/ajax-token",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = detailUrl
            ).text
            JSONObject(text).optString("t")
        }.getOrNull().orEmpty()

        if (token.isNotBlank() && type.isNotBlank() && id.isNotBlank()) {
            runCatching {
                app.post(
                    "$base/ajax-view",
                    data = mapOf(
                        "type" to type,
                        "id" to id,
                        "csrf_token" to token
                    ),
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to base,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = detailUrl
                )
            }
        }

        val text = runCatching {
            app.post(
                "$base/ajax",
                data = mapOf("cfg" to cfg),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to base,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json,*/*"
                ),
                referer = detailUrl
            ).text
        }.getOrNull() ?: return null

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val config = root.optJSONObject("config") ?: return null

        return config.optString("v")
            .replace("\\/", "/")
            .trim()
            .takeIf { it.startsWith("http") }
    }

    private fun extractHlsUrls(html: String): List<String> {
        val out = linkedSetOf<String>()

        val patterns = listOf(
            Regex(
                """file\s*:\s*["'](https?://[^"']+?\.m3u8(?:\?[^"']*)?)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """["']file["']\s*:\s*["'](https?://[^"']+?\.m3u8(?:\?[^"']*)?)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?)""",
                RegexOption.IGNORE_CASE
            )
        )

        for (regex in patterns) {
            regex.findAll(html).forEach { match ->
                val value = match.groupValues.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?.replace("&amp;", "&")
                    ?.trim()
                    ?: return@forEach
                if (value.startsWith("http")) out += value
            }
        }

        return out.toList()
    }

    private fun originOf(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault(url.substringBeforeLast("/") + "/")
    }
}
