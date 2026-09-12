package com.parsspor1

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class PARSSPOR1 : MainAPI() {
    private val jsonMapper = ObjectMapper()
    override var mainUrl = "https://www.papazsports1022.pro"
    override var name = "PARS SPOR 1"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val KNOWN_BASE = "https://www.papazsports1022.pro"
        private const val OFFICIAL_SHORTCUT = "https://bit.ly/m/papazsports"
        private const val DOMAIN_LIST = "https://ahatm12od.top/domain_list.json"
        @Volatile private var cachedBase: String? = null
        private val domainRegex = Regex("""https?://(?:www\.)?papazsports\d+\.[a-zA-Z0-9.-]+""", RegexOption.IGNORE_CASE)
    }

    data class ItemData(
        val kind: String, val title: String, val slug: String? = null,
        val target: String? = null, val source: String? = null,
        val sources: List<StreamSource>? = null, val poster: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamSource(
        @JsonProperty("ad") val label: String? = null,
        @JsonProperty("tip") val type: String? = null,
        @JsonProperty("link") val link: Any? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AuthResponse(
        @JsonProperty("URL") val url: String? = null,
        @JsonProperty("TOKEN") val token: String? = null,
        @JsonProperty("SERVER") val server: Any? = null
    )

    override val mainPage = mainPageOf("matches" to "Maç Listesi", "tv" to "7/24 TV")

    private fun cleanBase(s: String) = s.trim().trimEnd('/').replace("http://", "https://")

    private fun absolute(base: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            if (url.startsWith("http")) url else URI(base.trimEnd('/') + "/").resolve(url).toString()
        }.getOrNull()
    }

    private suspend fun isRealPapaz(base: String): Boolean = runCatching {
        val d = app.get(cleanBase(base), timeout = 8L, headers = mapOf("User-Agent" to USER_AGENT)).document
        val identity = d.title().contains("PapazSports", true) ||
            d.selectFirst("meta[name=generator]")?.attr("content").orEmpty().contains("PapazSports", true)
        val catalog = d.select("#mac-listesi li[data-source],#tv-listesi li[data-source],.channel-item[data-source]").isNotEmpty()
        identity && catalog
    }.getOrDefault(false)

    private suspend fun resolveBase(): String {
        cachedBase?.let { if (isRealPapaz(it)) return it }
        val c = LinkedHashSet<String>()
        c += KNOWN_BASE

        runCatching {
            val r = app.get(OFFICIAL_SHORTCUT, timeout = 10L, headers = mapOf("User-Agent" to USER_AGENT))
            domainRegex.findAll(r.text).forEach { c += cleanBase(it.value) }
            r.document.select("a[href]").forEach { a ->
                domainRegex.find(a.absUrl("href").ifBlank { a.attr("href") })?.value?.let { c += cleanBase(it) }
            }
        }

        runCatching {
            val body = app.get(DOMAIN_LIST, timeout = 8L, headers = mapOf("User-Agent" to USER_AGENT)).text
            Regex("""["']([^"']+)["']""").findAll(body).forEach {
                val x = it.groupValues[1].trim()
                val u = if (x.startsWith("http")) x else if (x.contains('.')) "https://$x" else ""
                if (u.isNotBlank()) c += cleanBase(u)
            }
        }

        for (x in c) if (isRealPapaz(x)) {
            cachedBase = cleanBase(x); mainUrl = cachedBase!!; return cachedBase!!
        }

        for (delta in 1..30) {
            val x = "https://www.papazsports${1022 + delta}.pro"
            if (isRealPapaz(x)) { cachedBase = x; mainUrl = x; return x }
        }
        for (delta in 1..5) {
            val x = "https://www.papazsports${1022 - delta}.pro"
            if (isRealPapaz(x)) { cachedBase = x; mainUrl = x; return x }
        }
        throw ErrorLoadingException("PARS SPOR 1: Güncel domain doğrulanamadı")
    }

    private fun Element.poster(base: String): String? {
        val i = selectFirst("img") ?: return null
        return absolute(base, i.attr("data-src").ifBlank { i.attr("src") })
    }

    private fun matchSources(raw: String): List<StreamSource> =
        runCatching { parseJson<List<StreamSource>>(raw) }.getOrDefault(emptyList())

    private fun matches(doc: Document, base: String): List<SearchResponse> =
        doc.select("#mac-listesi ul.match-list > li[data-source]").mapNotNull { e ->
            val home = e.selectFirst(".home")?.text()?.trim().orEmpty()
            val away = e.selectFirst(".away")?.text()?.trim().orEmpty()
            val time = e.selectFirst(".saat")?.text()?.trim().orEmpty()
            val title = if (home.isNotBlank() && away.isNotBlank())
                "$home - $away${if (time.isNotBlank()) " • $time" else ""}"
            else e.attr("data-title").substringBefore("|").replace("Canlı İzle","").trim()
            val ss = matchSources(e.attr("data-source"))
            if (title.isBlank() || ss.isEmpty()) return@mapNotNull null
            val d = ItemData("match", title, e.attr("data-url"), sources = ss, poster = e.poster(base))
            newMovieSearchResponse(title, jsonMapper.writeValueAsString(d), TvType.Live) { posterUrl = d.poster }
        }

    private fun tv(doc: Document, base: String): List<SearchResponse> =
        doc.select("#tv-listesi ul.match-list > li[data-source]").mapNotNull { e ->
            val title = e.attr("data-name").ifBlank { e.attr("data-search") }.trim()
            val source = e.attr("data-source").trim()
            if (title.isBlank() || source.isBlank()) return@mapNotNull null
            val d = ItemData("tv", title, e.attr("data-url"), e.attr("data-target"), source, poster = e.poster(base))
            newMovieSearchResponse(title, jsonMapper.writeValueAsString(d), TvType.Live) { posterUrl = d.poster }
        }.distinctBy { it.name.lowercase() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val b = resolveBase()
        val d = app.get("$b/", timeout = 12L, headers = mapOf("User-Agent" to USER_AGENT)).document
        return newHomePageResponse(request.name, if (request.data == "tv") tv(d,b) else matches(d,b))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val b = resolveBase()
        val d = app.get("$b/", timeout = 12L, headers = mapOf("User-Agent" to USER_AGENT)).document
        return (matches(d,b) + tv(d,b)).filter { it.name.contains(query.trim(), true) }.distinctBy { it.url }
    }
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val d = parseJson<ItemData>(url)
        return newMovieLoadResponse(d.title, url, TvType.Live, url) { posterUrl = d.poster }
    }

    private suspend fun emitHls(
        label: String,
        url: String,
        base: String,
        cb: (ExtractorLink)->Unit,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val playbackHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$base/",
            "Origin" to base,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-CH-UA" to "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"140\"",
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )
        playbackHeaders.putAll(extraHeaders.filterValues { it.isNotBlank() })

        cb(newExtractorLink("PARS SPOR 1", label, url.replace("\\/","/"), ExtractorLinkType.M3U8) {
            referer = "$base/"
            quality = Qualities.Unknown.value
            headers = playbackHeaders
        })
    }

    private suspend fun auth(id: String, label: String, base: String, cb: (ExtractorLink)->Unit): Boolean {
        if (id.isBlank()) return false
        val r = runCatching {
            app.post("$base/auth.php",
                data = mapOf("channel" to id.trim()),
                referer = "$base/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT, "Origin" to base,
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                ), timeout = 12L)
        }.getOrNull() ?: return false
        val a = runCatching { parseJson<AuthResponse>(r.text) }.getOrNull()
        val fresh = a?.url?.replace("\\/","/")?.trim().orEmpty()
        if (!fresh.startsWith("http")) return false

        // Resolver Lab V31.5 kanıtı:
        // CDN isteği standart 4 header ile 403, Chromium playback headerlarıyla 200.
        // TOKEN auth.php cevabından taze alınır; final CDN URL/TOKEN cache edilmez.
        val token = a?.token.orEmpty().trim()
        val playbackExtra = linkedMapOf<String, String>()
        if (token.isNotBlank()) {
            playbackExtra["UserToken"] = token
            playbackExtra["pl"] = token
        }

        emitHls(label, fresh, base, cb, playbackExtra)
        return true
    }

    private suspend fun one(s: StreamSource, base: String, cb: (ExtractorLink)->Unit): Boolean {
        val t = s.type.orEmpty().lowercase()
        val u = s.link?.toString()?.trim().orEmpty()
        val label = s.label?.ifBlank { "Canlı" } ?: "Canlı"
        if (u.isBlank()) return false
        return when(t) {
            "m3u8","hls" -> { emitHls(label,u,base,cb); true }
            "vip","viptv" -> auth(u,label,base,cb)
            else -> if (u.startsWith("http") && u.contains(".m3u8",true)) {
                emitHls(label,u,base,cb); true
            } else false
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile)->Unit, callback: (ExtractorLink)->Unit
    ): Boolean {
        val d = parseJson<ItemData>(data)
        val b = resolveBase()
        var ok = false
        if (d.kind == "tv") {
            ok = when(d.target.orEmpty().lowercase()) {
                "m3u8","hls" -> { emitHls(d.title,d.source.orEmpty(),b,callback); true }
                "vip","viptv" -> auth(d.source.orEmpty(),d.title,b,callback)
                else -> false
            }
        } else {
            for (s in d.sources.orEmpty()) if (one(s,b,callback)) ok = true
        }
        return ok
    }
}
