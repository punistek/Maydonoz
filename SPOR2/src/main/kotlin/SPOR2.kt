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
            Regex("""https?://[^'\"\\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:src|href)\\s*=\\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:fetch|getJSON)\\s*\\(\\s*['\"]?([^'\"\\s,)]+)""", RegexOption.IGNORE_CASE),
            Regex("""[^'\"\\s]+\\.m3u8(?:\\?[^'\"\\s]*)?""", RegexOption.IGNORE_CASE)
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
            """streamradardomil\\s*=\\s*\\[([^]]+)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(playerHtml)?.groupValues?.getOrNull(1)

        val b64 = block?.let {
            Regex("""atob\\(\\s*[\"']([^\"']+)[\"']\\s*\\)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        }
        val decoded = b64?.let { decodeBase64(it) }?.trim()?.trimStart('.')
        if (!decoded.isNullOrBlank()) return decoded

        // Fallback: player HTML'de e-aga-m hostunu doğrudan ara.
        return Regex("""(?:https?://)?([A-Za-z0-9.-]*e-aga-m[A-Za-z0-9.-]*\\.(?:sbs|com|net|click))""", RegexOption.IGNORE_CASE)
            .find(playerHtml)?.groupValues?.getOrNull(1)?.trim()?.trimStart('.')
    }

    private fun extractPathPrefix(playerHtml: String): String {
        // Sabit değeri hard-code fallback olarak tutuyoruz ama önce JS'den dinamik çıkarıyoruz.
        val fromJs = Regex(
            """/([a-f0-9]{24,64})/-/[^\"']*?playlist\\.m3u8""",
            RegexOption.IGNORE_CASE
        ).find(playerHtml)?.groupValues?.getOrNull(1)
        return fromJs ?: "bc2b05d321cb80050c5d035a9daeb26d"
    }

    private fun playerCandidates(state: SiteState, channel: Channel, channelHtml: String): List<String> {
        val out = linkedSetOf<String>()
        val doc = Jsoup.parse(channelHtml, channel.url)

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { absoluteUrl(channel.url, iframe.attr("src")) }
            if (src.isNotBlank()) out += src
        }

        Regex("""https?://[^'\"\\s<>]+(?:\\?[^'\"\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(channelHtml).take(80).forEach { m ->
                val u = m.value.replace("\\/", "/")
                if ("id=${channel.id}" in u || state.apiDomain?.let { it in u } == true) out += u
            }

        state.apiDomain?.let { api ->
            out += "https://${api.trim().trimEnd('/')}/?id=${channel.id}"
            out += "https://${api.trim().trimEnd('/')}?id=${channel.id}"
        }

        return out.filter { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun parseTokenSuffix(json: String): String {
        return try {
            val node = mapper.readTree(json)
            if (node.isArray && node.size() > 5 && !node[5].isNull) node[5].asText("") else ""
        } catch (t: Throwable) {
            warn("TOKEN_JSON_PARSE_ERROR", "body=${preview(json, 700)}", t)
            ""
        }
    }

    private suspend fun resolvePlayer(state: SiteState, channel: Channel): PlayerInfo? {
        diag("CHANNEL_PAGE_BEGIN", "id=${channel.id} GET ${channel.url}")
        val pageResponse = try {
            app.get(channel.url, headers = pageHeaders("${state.site}/"), cacheTime = 0)
        } catch (t: Throwable) {
            warn("CHANNEL_PAGE_ERROR", "url=${channel.url}", t)
            return null
        }
        val channelHtml = pageResponse.text
        diag(
            "CHANNEL_PAGE_HTTP",
            "url=${channel.url} code=${pageResponse.code} bytes=${channelHtml.length} diagnosis=${strictDiagnosis(pageResponse.code, channelHtml)} body=${preview(channelHtml, 900)}"
        )

        val pageHints = requestHints(channelHtml)
        diag("CHANNEL_PAGE_HINTS", "count=${pageHints.size} hints=${pageHints.take(25).joinToString(" || ")}")

        val candidates = playerCandidates(state, channel, channelHtml)
        diag("PLAYER_CANDIDATES", "id=${channel.id} count=${candidates.size} urls=${candidates.take(20).joinToString { safeUrl(it) }}")

        for (candidate0 in candidates) {
            var candidate = candidate0
            // iframe sabit bir başka ID taşıyorsa, ana sayfadaki gerçek kanal ID'sini tercih et.
            if (Regex("[?&]id=\\d+").containsMatchIn(candidate)) {
                candidate = candidate.replace(Regex("([?&]id=)\\d+"), "$1${channel.id}")
            }
            diag("PLAYER_TRY", "id=${channel.id} GET ${safeUrl(candidate)} referer=${channel.url}")
            try {
                val r = app.get(candidate, headers = pageHeaders(channel.url), cacheTime = 0)
                val html = r.text
                val diagnosis = strictDiagnosis(r.code, html)
                diag(
                    "PLAYER_HTTP",
                    "url=${safeUrl(candidate)} code=${r.code} bytes=${html.length} diagnosis=$diagnosis body=${preview(html, 1200)}"
                )
                val hints = requestHints(html)
                diag("PLAYER_HINTS", "url=${safeUrl(candidate)} hints=${hints.take(30).joinToString(" || ")}")

                if (r.code !in 200..299 || html.isBlank()) continue
                if (!("streamradardomil" in html || "playlist.m3u8" in html || "mainSource" in html)) {
                    diag("PLAYER_SIGNATURE_MISS", "url=${safeUrl(candidate)} expected player markers not found")
                    continue
                }

                val playerOrigin = originOf(candidate) ?: continue
                val host = extractStreamHost(html)
                val prefix = extractPathPrefix(html)
                diag(
                    "PLAYER_PARSED",
                    "playerOrigin=$playerOrigin sourceId=${channel.id} streamHost=${host ?: "NULL"} pathPrefix=$prefix"
                )
                if (host.isNullOrBlank()) {
                    warn("STREAM_HOST_PARSE_FAIL", "Player page found but streamradardomil host could not be decoded")
                    continue
                }

                val tokenUrl = "$playerOrigin/t?id=${channel.id}"
                diag("TOKEN_BEGIN", "GET ${safeUrl(tokenUrl)} referer=${safeUrl(candidate)}")
                val tokenResponse = try {
                    app.get(tokenUrl, headers = browserHeaders(state.site) + mapOf("Referer" to candidate), cacheTime = 0)
                } catch (t: Throwable) {
                    warn("TOKEN_ERROR", "url=${safeUrl(tokenUrl)}", t)
                    null
                }

                val tokenSuffix = if (tokenResponse != null) {
                    val body = tokenResponse.text
                    diag(
                        "TOKEN_HTTP",
                        "url=${safeUrl(tokenUrl)} code=${tokenResponse.code} bytes=${body.length} diagnosis=${strictDiagnosis(tokenResponse.code, body)} body=${preview(body, 700)}"
                    )
                    val suffix = parseTokenSuffix(body)
                    diag("TOKEN_PARSED", "index=5 present=${suffix.isNotEmpty()} length=${suffix.length}")
                    suffix
                } else ""

                return PlayerInfo(
                    playerUrl = candidate,
                    playerOrigin = playerOrigin,
                    sourceId = channel.id,
                    streamHost = host,
                    streamPathPrefix = prefix,
                    tokenSuffix = tokenSuffix
                )
            } catch (t: Throwable) {
                warn("PLAYER_ERROR", "candidate=${safeUrl(candidate)}", t)
            }
        }

        warn("PLAYER_RESOLVE_FAIL", "id=${channel.id} no usable player candidate")
        return null
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
        val minimal = probe("TEST_MINIMAL", streamUrl, minimalHeaders(site))
        val browser = probe("TEST_BROWSER_HEADERS", streamUrl, browserHeaders(site))
        val winner = when {
            browser.ok && !minimal.ok -> "BROWSER_HEADERS"
            browser.ok && minimal.ok -> "BOTH"
            minimal.ok -> "MINIMAL"
            else -> "NONE"
        }
        diag(
            "HEADER_COMPARISON",
            "minimalCode=${minimal.code} minimalHls=${minimal.isHls} browserCode=${browser.code} browserHls=${browser.isHls} winner=$winner"
        )

        val playlist = when {
            browser.isHls -> browser.body
            minimal.isHls -> minimal.body
            else -> ""
        }
        if (playlist.isBlank()) {
            warn("PLAYLIST_MISSING", "Neither probe returned #EXTM3U")
            return false
        }

        val media = firstMediaUri(playlist, streamUrl)
        if (media == null) {
            warn("FIRST_MEDIA_MISSING", "HLS playlist returned but no media URI was found")
            return true
        }
        diag("FIRST_MEDIA_FOUND", "url=${safeUrl(media)}")
        val segMin = probe("SEGMENT_MINIMAL", media, minimalHeaders(site), 180)
        val segBrowser = probe("SEGMENT_BROWSER_HEADERS", media, browserHeaders(site), 180)
        diag(
            "SEGMENT_COMPARISON",
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
