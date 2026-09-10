package com.pars.plugins

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class SPOR2 : MainAPI() {
    private val TAG = "SPOR2_DIAG"
    private val mapper = ObjectMapper()

    override var mainUrl = "https://izlemac529.sbs"
    override var name = "SPOR2"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true

    private val remoteConfigUrl =
        "https://raw.githubusercontent.com/punistek/Maydonoz/main/SPOR2/domains.json"

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    data class SiteConfig(
        @JsonProperty("site") val site: String? = null
    )

    private data class Channel(
        val id: String,
        val title: String,
        val url: String,
        val poster: String?
    )

    private data class SiteState(
        val site: String,
        val apiDomain: String?,
        val channels: List<Channel>
    )

    private data class PlayerInfo(
        val playerUrl: String,
        val playerOrigin: String,
        val sourceId: String,
        val streamHost: String,
        val streamPathPrefix: String,
        val tokenSuffix: String
    )

    private data class ProbeResult(
        val label: String,
        val code: Int,
        val body: String,
        val isHls: Boolean,
        val ok: Boolean
    )

    @Volatile
    private var cachedState: SiteState? = null

    private fun diag(stage: String, message: String) {
        Log.i(TAG, "[$stage] $message")
    }

    private fun warn(stage: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(TAG, "[$stage] $message")
        } else {
            Log.w(TAG, "[$stage] $message | ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private fun preview(text: String, max: Int = 900): String =
        text.replace(Regex("\\s+"), " ").trim().take(max)

    private fun normalizeSite(url: String): String = url.trim().trimEnd('/')

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Throwable) {
        null
    }

    private fun absoluteUrl(base: String, value: String): String {
        val v = value.trim()
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        return try {
            URI(normalizeSite(base) + "/").resolve(v).toString()
        } catch (_: Throwable) {
            if (v.startsWith("/")) normalizeSite(base) + v else normalizeSite(base) + "/" + v
        }
    }

    /**
     * Loglarda query değerlerini maskele. Host/path aynen görünür; imzalı tokenlar loga saçılmaz.
     */
    private fun safeUrl(url: String): String {
        return try {
            val u = URI(url)
            val q = u.rawQuery
            if (q.isNullOrBlank()) return url
            val safeQ = q.split("&").joinToString("&") { part ->
                val key = part.substringBefore("=", part)
                if ('=' in part) "$key=<redacted>" else key
            }
            URI(u.scheme, u.rawAuthority, u.rawPath, safeQ, u.rawFragment).toString()
        } catch (_: Throwable) {
            url.substringBefore('?') + if ('?' in url) "?<redacted>" else ""
        }
    }

    private fun strictDiagnosis(code: Int, text: String): String {
        val t = text.lowercase()
        val flags = mutableListOf<String>()
        if (text.trimStart().startsWith("#EXTM3U", ignoreCase = true)) flags += "HLS_PLAYLIST"
        if (code == 200) flags += "HTTP_200"
        if (code == 301 || code == 302 || code == 307 || code == 308) flags += "REDIRECT"
        if (code == 401) flags += "HTTP_401"
        if (code == 403) flags += "HTTP_403"
        if (code == 404) flags += "HTTP_404"
        if (code == 429) flags += "HTTP_429"
        if (code == 500) flags += "HTTP_500"
        if (code == 503) flags += "HTTP_503"
        if ("<title>attention required! | cloudflare</title>" in t) flags += "CF_ATTENTION_REQUIRED"
        if ("just a moment" in t && "cloudflare" in t) flags += "CF_JUST_A_MOMENT"
        if ("cloudflare ray id" in t || ("ray id" in t && "cloudflare" in t)) flags += "CF_RAY_ID"
        if ("/cdn-cgi/" in t && "cloudflare" in t) flags += "CF_ERROR_PAGE"
        if ("forbidden" in t) flags += "FORBIDDEN_TEXT"
        if ("unauthorized" in t) flags += "UNAUTHORIZED_TEXT"
        if ("playlist.m3u8" in t) flags += "PLAYLIST_TEMPLATE_TEXT"
        if ("chunklist" in t && ".m3u8" in t) flags += "CHUNKLIST_TEXT"
        if ("mainSource" in text || "mainsource" in t) flags += "MAINSOURCE_TEXT"
        if ("/t?id=" in t) flags += "TOKEN_ENDPOINT_TEXT"
        return if (flags.isEmpty()) "NONE" else flags.distinct().joinToString(",")
    }

    private fun requestHints(html: String): List<String> {
        val out = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^'"\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:src|href)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:fetch|getJSON)\s*\(\s*['"]?([^'"\s,)]+)""", RegexOption.IGNORE_CASE),
            Regex("""[^'"\s]+\.m3u8(?:\?[^'"\s]*)?""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { regex ->
            regex.findAll(html).take(40).forEach { m ->
                val value = if (m.groupValues.size > 1 && m.groupValues[1].isNotBlank()) m.groupValues[1] else m.value
                out += value.take(500)
            }
        }
        return out.take(40)
    }

    private fun browserHeaders(site: String): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Origin" to site,
        "Referer" to "$site/",
        "Sec-CH-UA" to "\"Google Chrome\";v=\"153\", \"Not_A Brand\";v=\"8\", \"Chromium\";v=\"153\"",
        "Sec-CH-UA-Mobile" to "?0",
        "Sec-CH-UA-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "User-Agent" to desktopUa
    )

    private fun minimalHeaders(site: String): Map<String, String> = mapOf(
        "Origin" to site,
        "Referer" to "$site/",
        "User-Agent" to desktopUa
    )

    private fun pageHeaders(referer: String? = null): Map<String, String> {
        val map = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "User-Agent" to desktopUa
        )
        if (!referer.isNullOrBlank()) map["Referer"] = referer
        return map
    }

    private suspend fun configuredSite(): String? {
        diag("CONFIG_BEGIN", "GET $remoteConfigUrl")
        return try {
            val r = app.get(remoteConfigUrl, cacheTime = 0)
            diag(
                "CONFIG_HTTP",
                "url=$remoteConfigUrl code=${r.code} bytes=${r.text.length} diagnosis=${strictDiagnosis(r.code, r.text)} body=${preview(r.text, 500)}"
            )
            val site = r.parsedSafe<SiteConfig>()?.site?.trim()?.takeIf { it.isNotEmpty() }
            diag("CONFIG_PARSED", "site=${site ?: "NULL"}")
            site
        } catch (t: Throwable) {
            warn("CONFIG_ERROR", "Remote config unavailable; built-in site discovery will continue", t)
            null
        }
    }

    private fun candidateSites(configured: String?): List<String> {
        val out = linkedSetOf<String>()
        fun add(v: String?) {
            val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return
            out += normalizeSite(s)
        }
        add(configured)
        add(mainUrl)
        for (n in 529..560) add("https://izlemac$n.sbs")
        return out.toList()
    }

    private fun parseChannel(site: String, a: Element): Channel? {
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        val title = a.selectFirst("strong.name")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: a.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        var id: String? = null
        a.select("[class]").forEach { el ->
            if (id == null) {
                id = Regex("(?:^|\\s)tvicx-(\\d+)(?:\\s|$)")
                    .find(el.className())?.groupValues?.getOrNull(1)
            }
        }
        if (id == null) {
            id = Regex("tvicx-(\\d+)").find(a.outerHtml())?.groupValues?.getOrNull(1)
        }
        val finalId = id?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val image = a.selectFirst("img")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
        return Channel(
            id = finalId,
            title = title,
            url = absoluteUrl(site, href),
            poster = image?.let { absoluteUrl(site, it) }
        )
    }

    private suspend fun resolveSite(force: Boolean = false): SiteState? {
        if (!force) {
            cachedState?.let {
                diag("SITE_CACHE_HIT", "site=${it.site} apiDomain=${it.apiDomain ?: "NULL"} channels=${it.channels.size}")
                return it
            }
        }

        val configured = configuredSite()
        val candidates = candidateSites(configured)
        diag("SITE_DISCOVERY_BEGIN", "force=$force candidates=${candidates.joinToString()}")

        for (candidate in candidates) {
            diag("SITE_TRY", "GET $candidate")
            try {
                val r = app.get(candidate, headers = pageHeaders(), cacheTime = 0)
                val html = r.text
                diag(
                    "SITE_HTTP",
                    "url=$candidate code=${r.code} bytes=${html.length} diagnosis=${strictDiagnosis(r.code, html)} body=${preview(html, 700)}"
                )
                if (r.code !in 200..299 || html.isBlank()) continue

                val doc = Jsoup.parse(html, candidate)
                val apiDomain = doc.body()?.attr("data-api-domain")?.trim()?.takeIf { it.isNotBlank() }
                diag("API_DOMAIN_DISCOVERY", "site=$candidate apiDomain=${apiDomain ?: "NULL"}")

                val rawLinks = doc.select(".item.live a[href*='/canli-mac-izle/'], a.dblock[href*='/canli-mac-izle/']")
                diag("CHANNEL_SELECTOR", "site=$candidate rawMatches=${rawLinks.size}")
                val channels = rawLinks.mapNotNull { parseChannel(candidate, it) }.distinctBy { it.id }
                diag(
                    "CHANNELS_PARSED",
                    "count=${channels.size} sample=${channels.take(12).joinToString { "${it.id}:${it.title}" }}"
                )

                if (channels.isEmpty()) {
                    val hints = requestHints(html)
                    diag("SITE_HINTS", "hints=${hints.take(20).joinToString(" || ")}")
                    continue
                }

                val state = SiteState(normalizeSite(candidate), apiDomain, channels)
                cachedState = state
                mainUrl = state.site
                diag("SITE_OK", "site=${state.site} apiDomain=${state.apiDomain ?: "NULL"} channels=${channels.size}")
                return state
            } catch (t: Throwable) {
                warn("SITE_ERROR", "candidate=$candidate", t)
            }
        }

        warn("SITE_DISCOVERY_FAIL", "No working izlemaç domain with live channel IDs was found")
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val state = resolveSite() ?: return newHomePageResponse(
            listOf(HomePageList("Kanallar", emptyList())),
            hasNext = false
        )

        val items = state.channels.map { ch ->
            newLiveSearchResponse(ch.title, ch.url, TvType.Live) {
                posterUrl = ch.poster
            }
        }
        return newHomePageResponse(
            listOf(HomePageList("Kanallar", items, isHorizontalImages = false)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val state = resolveSite() ?: return emptyList()
        return state.channels.filter { it.title.contains(q, true) }.map { ch ->
            newLiveSearchResponse(ch.title, ch.url, TvType.Live) { posterUrl = ch.poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        diag("LOAD_BEGIN", "url=$url")
        val state = resolveSite() ?: return null
        val normalized = url.trimEnd('/')
        var channel = state.channels.firstOrNull { it.url.trimEnd('/') == normalized }

        if (channel == null) {
            diag("LOAD_CACHE_MISS", "url=$url -> force refreshing channel list")
            cachedState = null
            val fresh = resolveSite(true) ?: return null
            channel = fresh.channels.firstOrNull { it.url.trimEnd('/') == normalized }
        }

        val ch = channel ?: run {
            warn("LOAD_CHANNEL_NOT_FOUND", "url=$url")
            return null
        }
        diag("LOAD_OK", "id=${ch.id} title=${ch.title} page=${ch.url}")
        return newLiveStreamLoadResponse(ch.title, ch.url, ch.url) {
            posterUrl = ch.poster
        }
    }

    private fun decodeBase64(value: String): String? = try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    private fun extractStreamHost(playerHtml: String): String? {
        // Güncel player JS: window.streamradardomil=[atob("LmUtYWdhLW0u...")]
        val block = Regex(
            """streamradardomil\s*=\s*\[([^]]+)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(playerHtml)?.groupValues?.getOrNull(1)

        val b64 = block?.let {
            Regex("""atob\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        }
        val decoded = b64?.let { decodeBase64(it) }?.trim()?.trimStart('.')
        if (!decoded.isNullOrBlank()) {
            diag("STREAM_DOMAIN_FOUND", "method=streamradardomil_atob host=$decoded")
            return decoded
        }

        // Fallback: HTML içinde e-aga-m ailesini doğrudan ara.
        val direct = Regex(
            """(?:https?://)?([A-Za-z0-9.-]*e-aga-m[A-Za-z0-9.-]*\.(?:sbs|com|net|click))""",
            RegexOption.IGNORE_CASE
        ).find(playerHtml)?.groupValues?.getOrNull(1)?.trim()?.trimStart('.')
        if (!direct.isNullOrBlank()) {
            diag("STREAM_DOMAIN_FOUND", "method=direct_regex host=$direct")
        }
        return direct
    }

    private fun extractPathPrefix(playerHtml: String): String {
        val fromJs = Regex(
            """/([a-f0-9]{24,64})/-/[^"']*?playlist\.m3u8""",
            RegexOption.IGNORE_CASE
        ).find(playerHtml)?.groupValues?.getOrNull(1)
        val prefix = fromJs ?: "bc2b05d321cb80050c5d035a9daeb26d"
        diag("STREAM_PREFIX_FOUND", "method=${if (fromJs != null) "html" else "fallback"} prefix=$prefix")
        return prefix
    }

    private fun parseTokenSuffix(json: String): String {
        return try {
            val node = mapper.readTree(json)
            if (node.isArray && node.size() > 5 && !node[5].isNull) node[5].asText("") else ""
        } catch (t: Throwable) {
            warn("T_JSON_PARSE_ERROR", "body=${preview(json, 700)}", t)
            ""
        }
    }

    private fun matchCenterUrl(site: String, id: String): String =
        "$site/wp-content/themes/ikisifirbirdokuz/match-center.php?id=$id"

    private fun tHeaders(site: String, matchCenter: String): Map<String, String> = mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to matchCenter,
        "Sec-CH-UA" to "\"Google Chrome\";v=\"153\", \"Not_A Brand\";v=\"8\", \"Chromium\";v=\"153\"",
        "Sec-CH-UA-Mobile" to "?0",
        "Sec-CH-UA-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "User-Agent" to desktopUa,
        "X-Requested-With" to "XMLHttpRequest"
    )

    private suspend fun resolvePlayer(state: SiteState, channel: Channel): PlayerInfo? {
        val matchCenter = matchCenterUrl(state.site, channel.id)
        diag("CHANNEL_ID", "id=${channel.id} title=${channel.title} page=${channel.url}")
        diag("MATCH_CENTER_URL", "url=${safeUrl(matchCenter)}")
        diag("MATCH_CENTER_BEGIN", "GET ${safeUrl(matchCenter)} referer=${channel.url}")

        val r = try {
            app.get(matchCenter, headers = pageHeaders(channel.url), cacheTime = 0)
        } catch (t: Throwable) {
            warn("MATCH_CENTER_ERROR", "url=${safeUrl(matchCenter)}", t)
            return null
        }
        val html = r.text
        diag(
            "MATCH_CENTER_HTTP",
            "url=${safeUrl(matchCenter)} code=${r.code} bytes=${html.length} diagnosis=${strictDiagnosis(r.code, html)} body=${preview(html, 1400)}"
        )
        if (r.code !in 200..299 || html.isBlank()) {
            warn("MATCH_CENTER_FAIL", "id=${channel.id} code=${r.code}")
            return null
        }

        val hints = requestHints(html)
        diag("MATCH_CENTER_HINTS", "count=${hints.size} hints=${hints.take(35).joinToString(" || ")}")

        val hasMainSource = "mainSource" in html || "mainsource" in html.lowercase()
        val hasPlaylist = "playlist.m3u8" in html.lowercase()
        val hasStreamRadar = "streamradardomil" in html.lowercase()
        diag(
            "MATCH_CENTER_SIGNATURE",
            "mainSource=$hasMainSource playlist=$hasPlaylist streamradardomil=$hasStreamRadar"
        )

        val host = extractStreamHost(html)
        val prefix = extractPathPrefix(html)
        if (host.isNullOrBlank()) {
            warn("STREAM_DOMAIN_PARSE_FAIL", "match-center loaded but e-aga-m/streamradardomil host was not found")
            return null
        }

        val tUrl = "${state.site}/t?id=${channel.id}"
        diag("T_REQUEST_BEGIN", "GET ${safeUrl(tUrl)} referer=${safeUrl(matchCenter)} xRequestedWith=XMLHttpRequest cookies=NONE")
        val tr = try {
            app.get(tUrl, headers = tHeaders(state.site, matchCenter), cacheTime = 0)
        } catch (t: Throwable) {
            warn("T_REQUEST_ERROR", "url=${safeUrl(tUrl)}", t)
            return null
        }
        val tBody = tr.text
        diag(
            "T_REQUEST_HTTP",
            "url=${safeUrl(tUrl)} code=${tr.code} bytes=${tBody.length} diagnosis=${strictDiagnosis(tr.code, tBody)} body=${preview(tBody, 900)}"
        )
        diag("T_RESPONSE_JSON", "body=${preview(tBody, 900)}")
        if (tr.code !in 200..299) {
            warn("T_REQUEST_FAIL", "id=${channel.id} code=${tr.code}; no cookie/challenge replay attempted")
            return null
        }

        val suffix = parseTokenSuffix(tBody)
        diag(
            "T_SUFFIX_FOUND",
            "present=${suffix.isNotEmpty()} length=${suffix.length} startsWithQuestion=${suffix.startsWith("?")}"
        )

        return PlayerInfo(
            playerUrl = matchCenter,
            playerOrigin = state.site,
            sourceId = channel.id,
            streamHost = host,
            streamPathPrefix = prefix,
            tokenSuffix = suffix
        )
    }

    private suspend fun probe(label: String, url: String, headers: Map<String, String>, previewLimit: Int = 1000): ProbeResult {
        diag("${label}_BEGIN", "GET ${safeUrl(url)} headers=$headers")
        return try {
            val r = app.get(url, headers = headers, cacheTime = 0)
            val text = r.text
            val isHls = text.trimStart().startsWith("#EXTM3U", ignoreCase = true)
            val ok = r.code in 200..299 && (isHls || !url.contains(".m3u8", true))
            diag(
                "${label}_HTTP",
                "url=${safeUrl(url)} code=${r.code} bytes=${text.length} isHls=$isHls diagnosis=${strictDiagnosis(r.code, text)} body=${preview(text, previewLimit)}"
            )
            ProbeResult(label, r.code, text, isHls, ok)
        } catch (t: Throwable) {
            warn("${label}_ERROR", "url=${safeUrl(url)}", t)
            ProbeResult(label, -1, "", false, false)
        }
    }

    private fun firstMediaUri(playlist: String, playlistUrl: String): String? {
        val raw = playlist.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
        return try { URI(playlistUrl).resolve(raw).toString() } catch (_: Throwable) { raw }
    }

    private suspend fun deepDiagnostics(streamUrl: String, site: String): Boolean {
        diag("PLAYLIST_BUILT", "url=${safeUrl(streamUrl)}")
        val minimal = probe("PLAYLIST_MINIMAL", streamUrl, minimalHeaders(site))
        val browser = probe("PLAYLIST_BROWSER", streamUrl, browserHeaders(site))
        val winner = when {
            browser.ok && !minimal.ok -> "BROWSER_HEADERS"
            browser.ok && minimal.ok -> "BOTH"
            minimal.ok -> "MINIMAL"
            else -> "NONE"
        }
        diag(
            "PLAYLIST_COMPARE",
            "minimalCode=${minimal.code} minimalHls=${minimal.isHls} browserCode=${browser.code} browserHls=${browser.isHls} winner=$winner"
        )

        val playlistResult = when {
            browser.isHls -> browser
            minimal.isHls -> minimal
            else -> null
        }
        if (playlistResult == null) {
            warn("PLAYLIST_MISSING", "Neither probe returned #EXTM3U")
            return false
        }

        val first = firstMediaUri(playlistResult.body, streamUrl)
        if (first == null) {
            warn("PLAYLIST_FIRST_URI_MISSING", "HLS playlist returned but no child/media URI was found")
            return true
        }

        // Master playlist ise önce chunklist'i aç, sonra gerçek segmenti test et.
        val firstLooksPlaylist = first.contains(".m3u8", ignoreCase = true)
        val segmentUrl: String
        if (firstLooksPlaylist) {
            diag("CHUNKLIST_FOUND", "url=${safeUrl(first)}")
            val chunk = probe("CHUNKLIST_BROWSER", first, browserHeaders(site), 1200)
            if (!chunk.isHls) {
                warn("CHUNKLIST_FAIL", "code=${chunk.code} url=${safeUrl(first)}")
                return playlistResult.ok
            }
            val segment = firstMediaUri(chunk.body, first)
            if (segment == null) {
                warn("SEGMENT_MISSING", "Chunklist returned #EXTM3U but no segment URI was found")
                return true
            }
            segmentUrl = segment
        } else {
            segmentUrl = first
        }

        diag("SEGMENT_FOUND", "url=${safeUrl(segmentUrl)}")
        val segMin = probe("SEGMENT_MINIMAL", segmentUrl, minimalHeaders(site), 180)
        val segBrowser = probe("SEGMENT_BROWSER", segmentUrl, browserHeaders(site), 180)
        diag(
            "SEGMENT_COMPARE",
            "minimalCode=${segMin.code} browserCode=${segBrowser.code} minimalBytes=${segMin.body.length} browserBytes=${segBrowser.body.length}"
        )
        return browser.ok || minimal.ok
    }

    private suspend fun resolveStream(state: SiteState, channel: Channel): Pair<String, Map<String, String>>? {
        diag("RESOLVE_STREAM_BEGIN", "id=${channel.id} title=${channel.title}")
        val player = resolvePlayer(state, channel) ?: return null

        val base = "https://${player.streamHost}/${player.streamPathPrefix}/-/${player.sourceId}/playlist.m3u8"
        val streamUrl = base + player.tokenSuffix
        diag(
            "STREAM_URL_BUILT",
            "host=${player.streamHost} prefix=${player.streamPathPrefix} id=${player.sourceId} tokenSuffixPresent=${player.tokenSuffix.isNotEmpty()} url=${safeUrl(streamUrl)}"
        )

        val probeOk = deepDiagnostics(streamUrl, state.site)
        diag("STREAM_RESOLVE_RESULT", "id=${channel.id} probeOk=$probeOk url=${safeUrl(streamUrl)}")

        // SPOR1'de doğruladığımız normal tarayıcı header profili. Cookie/challenge yok.
        val headers = browserHeaders(state.site)
        return streamUrl to headers
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val trace = System.currentTimeMillis().toString(16)
        diag("LOAD_LINKS_BEGIN", "trace=$trace data=$data casting=$isCasting")

        var state = resolveSite() ?: return false
        var channel = state.channels.firstOrNull { it.url.trimEnd('/') == data.trimEnd('/') }
        if (channel == null) {
            warn("LOAD_LINKS_CHANNEL_CACHE_MISS", "trace=$trace data=$data -> force refresh")
            cachedState = null
            state = resolveSite(true) ?: return false
            channel = state.channels.firstOrNull { it.url.trimEnd('/') == data.trimEnd('/') }
        }
        val ch = channel ?: run {
            warn("LOAD_LINKS_CHANNEL_FAIL", "trace=$trace channel not found for $data")
            return false
        }

        diag("LOAD_LINKS_CHANNEL", "trace=$trace id=${ch.id} title=${ch.title} url=${ch.url}")
        val resolved = resolveStream(state, ch) ?: run {
            warn("LOAD_LINKS_RESOLVE_FAIL", "trace=$trace id=${ch.id}")
            return false
        }
        val streamUrl = resolved.first
        val playerHeaders = resolved.second

        diag(
            "CALLBACK_SEND",
            "trace=$trace type=M3U8 url=${safeUrl(streamUrl)} referer=${state.site}/ headers=$playerHeaders"
        )
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "${state.site}/"
                headers = playerHeaders
                quality = Qualities.Unknown.value
            }
        )
        diag("LOAD_LINKS_OK", "trace=$trace callbackCount=1 id=${ch.id} url=${safeUrl(streamUrl)}")
        return true
    }
}
