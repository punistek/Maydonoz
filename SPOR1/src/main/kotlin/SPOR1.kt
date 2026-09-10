package com.pars.plugins

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class SPOR1 : MainAPI() {
    private val TAG = "SPOR1_DIAG"

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

    private fun preview(text: String, max: Int = 700): String =
        text.replace(Regex("\\s+"), " ").trim().take(max)

    private fun bodyDiagnosis(text: String): String {
        val t = text.lowercase()
        val flags = mutableListOf<String>()
        if ("#extm3u" in t) flags += "HLS_PLAYLIST"
        if ("cloudflare" in t) flags += "CLOUDFLARE"
        if ("attention required" in t) flags += "CF_ATTENTION_REQUIRED"
        if ("just a moment" in t) flags += "CF_JUST_A_MOMENT"
        if ("this content has been restricted" in t) flags += "CF_CONTENT_RESTRICTED"
        if ("ray id" in t) flags += "CF_RAY_ID"
        if ("captcha" in t) flags += "CAPTCHA"
        if ("unauthorized" in t || "not authorized" in t) flags += "AUTH_REJECT"
        if ("forbidden" in t) flags += "FORBIDDEN_TEXT"
        if ("token" in t) flags += "TOKEN_TEXT"
        if ("signature" in t) flags += "SIGNATURE_TEXT"
        if ("api key" in t || "apikey" in t || "api_key" in t) flags += "API_KEY_TEXT"
        if ("login" in t || "sign in" in t) flags += "LOGIN_TEXT"
        if ("domain.php" in t) flags += "DOMAIN_ENDPOINT_TEXT"
        if ("channels.php" in t) flags += "CHANNELS_ENDPOINT_TEXT"
        if ("mono.m3u8" in t || ".m3u8" in t) flags += "M3U8_TEXT"
        return if (flags.isEmpty()) "NONE" else flags.distinct().joinToString(",")
    }

    private fun extractRequestHints(html: String): List<String> {
        val out = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""fetch\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:url|domainUrl|baseUrl)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](https?://[^'"\s]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"]([^'"]+\.(?:php|m3u8)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            regex.findAll(html).take(40).forEach { m ->
                val value = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (value.isNotEmpty()) out += value.take(500)
            }
        }
        return out.take(40)
    }
    override var mainUrl = "https://tambettv23.com"
    override var name = "SPOR1"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true

    private val remoteConfigUrl =
        "https://raw.githubusercontent.com/punistek/Maydonoz/main/SPOR1/domains.json"

    data class SiteConfig(
        @JsonProperty("site") val site: String? = null
    )

    data class DomainResponse(
        @JsonProperty("baseurl") val baseurl: String? = null
    )

    private data class Endpoints(
        val site: String,
        val channelsUrl: String,
        val domainUrl: String
    )

    private data class Channel(
        val id: String,
        val title: String,
        val poster: String?
    )

    @Volatile
    private var cachedEndpoints: Endpoints? = null

    private val blockedHosts = mutableMapOf<String, Long>()
    private val blockedForMs = 5 * 60 * 1000L

    private fun hostOf(url: String): String? = try { URI(url).host?.lowercase() } catch (_: Throwable) { null }

    private fun isBlocked(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val until = blockedHosts[host] ?: return false
        if (System.currentTimeMillis() >= until) {
            blockedHosts.remove(host)
            return false
        }
        return true
    }

    private fun markBlocked(url: String) {
        hostOf(url)?.let { blockedHosts[it] = System.currentTimeMillis() + blockedForMs }
    }

    private fun normalizeSite(url: String): String =
        url.trim().trimEnd('/')

    private fun originOf(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Throwable) {
            null
        }
    }

    private fun absoluteUrl(base: String, value: String): String {
        val v = value.trim()
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        return if (v.startsWith("/")) {
            normalizeSite(base) + v
        } else {
            normalizeSite(base) + "/" + v
        }
    }

    private fun candidateSites(configured: String?): List<String> {
        val out = linkedSetOf<String>()

        fun add(url: String?) {
            val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
            out += normalizeSite(value)
        }

        add(configured)
        add(mainUrl)

        // Mevcut numaralı TamBet alan adının ileri sürümlerini otomatik dener.
        // 23 -> 24 -> 25 -> ... -> 40
        for (n in 23..40) {
            add("https://tambettv$n.com")
        }

        return out.toList()
    }

    private suspend fun configuredSite(): String? {
        diag("CONFIG_BEGIN", "GET $remoteConfigUrl")
        return try {
            val response = app.get(remoteConfigUrl, cacheTime = 0)
            val text = response.text
            diag(
                "CONFIG_HTTP",
                "url=$remoteConfigUrl code=${response.code} bytes=${text.length} diagnosis=${bodyDiagnosis(text)} body=${preview(text)}"
            )
            val site = response.parsedSafe<SiteConfig>()?.site?.trim()?.takeIf { it.isNotEmpty() }
            diag("CONFIG_PARSED", "site=${site ?: "NULL"}")
            site
        } catch (t: Throwable) {
            warn("CONFIG_ERROR", "url=$remoteConfigUrl", t)
            null
        }
    }

    private fun extractFetchUrl(html: String, fileName: String): String? {
        val regex = Regex(
            """fetch\(\s*['"]([^'"]*${Regex.escape(fileName)}[^'"]*)['"]""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun extractDomainUrl(html: String): String? {
        val regex = Regex(
            """domainUrl\s*:\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun looksLikeTamBet(html: String): Boolean {
        val text = html.lowercase()
        return (
            "channels.php" in text &&
            ("channel?id=" in text || "kanallar" in text || "domain.php" in text)
        )
    }

    private suspend fun resolveEndpoints(force: Boolean = false): Endpoints? {
        if (!force) {
            cachedEndpoints?.let {
                diag("ENDPOINT_CACHE_HIT", "site=${it.site} channels=${it.channelsUrl} domain=${it.domainUrl}")
                return it
            }
        }

        val configured = configuredSite()
        val candidates = candidateSites(configured)
        diag("ENDPOINT_BEGIN", "force=$force configured=${configured ?: "NULL"} candidates=${candidates.joinToString()}")

        for (candidate in candidates) {
            diag("SITE_TRY", "GET $candidate")
            try {
                val response = app.get(candidate, cacheTime = 0)
                val html = response.text
                diag(
                    "SITE_HTTP",
                    "url=$candidate code=${response.code} bytes=${html.length} diagnosis=${bodyDiagnosis(html)} body=${preview(html)}"
                )

                val hints = extractRequestHints(html)
                if (hints.isNotEmpty()) {
                    diag("SITE_REQUEST_HINTS", "url=$candidate hints=${hints.joinToString(" || ")}")
                }

                val tamBet = looksLikeTamBet(html)
                diag("SITE_SIGNATURE", "url=$candidate looksLikeTamBet=$tamBet")
                if (!tamBet) continue

                val site = normalizeSite(candidate)
                val channelsRaw = extractFetchUrl(html, "channels.php")
                diag("CHANNELS_DISCOVERY", "site=$site extracted=${channelsRaw ?: "NULL"}")
                val finalChannelsRaw = channelsRaw ?: "https://data-reality.com/channels.php"

                var domainRaw = extractDomainUrl(html)
                diag("DOMAIN_DISCOVERY_MAIN", "site=$site extracted=${domainRaw ?: "NULL"}")

                if (domainRaw.isNullOrBlank()) {
                    val playerUrl = "$site/channel?id=zirve"
                    diag("PLAYER_PROBE_BEGIN", "GET $playerUrl referer=$site/")
                    val playerResponse = app.get(playerUrl, referer = "$site/", cacheTime = 0)
                    val playerHtml = playerResponse.text
                    diag(
                        "PLAYER_PROBE_HTTP",
                        "url=$playerUrl code=${playerResponse.code} bytes=${playerHtml.length} diagnosis=${bodyDiagnosis(playerHtml)} body=${preview(playerHtml)}"
                    )
                    val playerHints = extractRequestHints(playerHtml)
                    if (playerHints.isNotEmpty()) {
                        diag("PLAYER_REQUEST_HINTS", "hints=${playerHints.joinToString(" || ")}")
                    }
                    domainRaw = extractDomainUrl(playerHtml)
                    diag("DOMAIN_DISCOVERY_PLAYER", "extracted=${domainRaw ?: "NULL"}")
                }

                val channelsUrl = absoluteUrl(site, finalChannelsRaw)
                val domainUrl = absoluteUrl(site, domainRaw ?: "https://data-reality.com/domain.php")

                val endpoints = Endpoints(site, channelsUrl, domainUrl)
                diag("ENDPOINT_OK", "site=$site channelsUrl=$channelsUrl domainUrl=$domainUrl")
                cachedEndpoints = endpoints
                mainUrl = site
                return endpoints
            } catch (t: Throwable) {
                warn("SITE_ERROR", "candidate=$candidate", t)
            }
        }

        warn("ENDPOINT_FAIL", "No working TamBet endpoint found")
        return null
    }

    private fun Element.toChannel(site: String): Channel? {
        val href = attr("href")
        val id = Regex("""(?:\?|&)id=([^&]+)""")
            .find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val title = selectFirst(".teams .home")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val image = selectFirst(".teams .away img")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val poster = image?.let { absoluteUrl(site, it) }

        return Channel(
            id = id,
            title = title,
            poster = poster
        )
    }

    private suspend fun getChannels(forceResolve: Boolean = false): Pair<Endpoints, List<Channel>>? {
        val endpoints = resolveEndpoints(forceResolve) ?: return null
        diag("CHANNELS_BEGIN", "GET ${endpoints.channelsUrl} referer=${endpoints.site}/ force=$forceResolve")

        return try {
            val response = app.get(endpoints.channelsUrl, referer = "${endpoints.site}/", cacheTime = 0)
            val text = response.text
            diag(
                "CHANNELS_HTTP",
                "url=${endpoints.channelsUrl} code=${response.code} bytes=${text.length} diagnosis=${bodyDiagnosis(text)} body=${preview(text)}"
            )
            val document = Jsoup.parse(text, endpoints.channelsUrl)
            val channels = document.select("a.single-match[href*='channel?id=']")
                .mapNotNull { it.toChannel(endpoints.site) }
                .distinctBy { it.id }

            diag(
                "CHANNELS_PARSED",
                "count=${channels.size} sample=${channels.take(8).joinToString { "${it.id}:${it.title}" }}"
            )

            if (channels.isEmpty() && !forceResolve) {
                warn("CHANNELS_EMPTY_RETRY", "Clearing endpoint cache and resolving again")
                cachedEndpoints = null
                return getChannels(true)
            }
            endpoints to channels
        } catch (t: Throwable) {
            warn("CHANNELS_ERROR", "url=${endpoints.channelsUrl} force=$forceResolve", t)
            if (!forceResolve) {
                cachedEndpoints = null
                getChannels(true)
            } else null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val result = getChannels()
            ?: return newHomePageResponse(
                listOf(HomePageList("Kanallar", emptyList())),
                hasNext = false
            )

        val endpoints = result.first
        val channels = result.second

        val items = channels.map { channel ->
            newLiveSearchResponse(
                channel.title,
                "${endpoints.site}/channel?id=${channel.id}",
                TvType.Live
            ) {
                posterUrl = channel.poster
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Kanallar",
                    items,
                    isHorizontalImages = false
                )
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val result = getChannels() ?: return emptyList()
        val endpoints = result.first

        return result.second
            .filter { it.title.contains(q, ignoreCase = true) }
            .map { channel ->
                newLiveSearchResponse(
                    channel.title,
                    "${endpoints.site}/channel?id=${channel.id}",
                    TvType.Live
                ) {
                    posterUrl = channel.poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        diag("LOAD_BEGIN", "url=$url")
        val id = Regex("""(?:\?|&)id=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val result = getChannels()
        val endpoints = result?.first ?: resolveEndpoints() ?: return null
        val channel = result?.second?.firstOrNull { it.id == id }

        val title = channel?.title ?: id.uppercase()
        val dataUrl = "${endpoints.site}/channel?id=$id"
        diag("LOAD_OK", "id=$id title=$title dataUrl=$dataUrl")

        return newLiveStreamLoadResponse(
            title,
            dataUrl,
            dataUrl
        ) {
            posterUrl = channel?.poster
        }
    }

    private data class ProbeResult(
        val label: String,
        val code: Int,
        val isHls: Boolean,
        val body: String,
        val ok: Boolean
    )

    private fun minimalHeaders(site: String): Map<String, String> = mapOf(
        "Origin" to site,
        "Referer" to "$site/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    )

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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    )

    private fun strictCloudflareDiagnosis(code: Int, text: String): String {
        val t = text.lowercase()
        val flags = mutableListOf<String>()
        if (text.trimStart().startsWith("#EXTM3U", ignoreCase = true)) flags += "HLS_PLAYLIST"
        if (code == 403) flags += "HTTP_403"
        if (code == 429) flags += "HTTP_429"
        if (code == 503) flags += "HTTP_503"
        if ("<title>attention required! | cloudflare</title>" in t) flags += "CF_ATTENTION_REQUIRED"
        if ("just a moment" in t && "cloudflare" in t) flags += "CF_JUST_A_MOMENT"
        if ("this content has been restricted" in t && "cloudflare" in t) flags += "CF_CONTENT_RESTRICTED"
        if ("cloudflare ray id" in t || ("ray id" in t && "cloudflare" in t)) flags += "CF_RAY_ID"
        if ("/cdn-cgi/" in t && "cloudflare" in t) flags += "CF_ERROR_PAGE"
        return if (flags.isEmpty()) "NONE" else flags.distinct().joinToString(",")
    }

    private suspend fun probe(
        label: String,
        url: String,
        headers: Map<String, String>,
        bodyPreviewLimit: Int = 1200
    ): ProbeResult {
        diag("${label}_BEGIN", "GET $url headers=$headers")
        return try {
            val response = app.get(url, headers = headers, cacheTime = 0)
            val text = response.text
            val isHls = text.trimStart().startsWith("#EXTM3U", ignoreCase = true)
            val ok = response.code in 200..299 && (isHls || !url.contains(".m3u8", ignoreCase = true))
            val diagnosis = strictCloudflareDiagnosis(response.code, text)
            diag(
                "${label}_HTTP",
                "url=$url code=${response.code} bytes=${text.length} isHls=$isHls diagnosis=$diagnosis body=${preview(text, bodyPreviewLimit)}"
            )
            ProbeResult(label, response.code, isHls, text, ok)
        } catch (t: Throwable) {
            warn("${label}_ERROR", "url=$url", t)
            ProbeResult(label, -1, false, "", false)
        }
    }

    private fun firstSegmentFromPlaylist(playlist: String, playlistUrl: String): String? {
        val first = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        return try {
            URI(playlistUrl).resolve(first).toString()
        } catch (_: Throwable) {
            first
        }
    }

    private suspend fun runDeepStreamDiagnostics(
        streamUrl: String,
        site: String
    ): Boolean {
        val minimal = probe("TEST_MINIMAL", streamUrl, minimalHeaders(site))
        val browser = probe("TEST_BROWSER_HEADERS", streamUrl, browserHeaders(site))

        diag(
            "HEADER_COMPARISON",
            "minimalCode=${minimal.code} minimalHls=${minimal.isHls} browserCode=${browser.code} browserHls=${browser.isHls} winner=" +
                when {
                    browser.ok && !minimal.ok -> "BROWSER_HEADERS"
                    minimal.ok && browser.ok -> "BOTH"
                    minimal.ok -> "MINIMAL"
                    else -> "NONE"
                }
        )

        val playlist = when {
            browser.isHls -> browser.body
            minimal.isHls -> minimal.body
            else -> ""
        }

        if (playlist.isNotBlank()) {
            val segmentUrl = firstSegmentFromPlaylist(playlist, streamUrl)
            if (segmentUrl != null) {
                diag("FIRST_SEGMENT_FOUND", "url=$segmentUrl")
                val segMinimal = probe("SEGMENT_MINIMAL", segmentUrl, minimalHeaders(site), 220)
                val segBrowser = probe("SEGMENT_BROWSER_HEADERS", segmentUrl, browserHeaders(site), 220)
                diag(
                    "SEGMENT_COMPARISON",
                    "minimalCode=${segMinimal.code} browserCode=${segBrowser.code} " +
                        "minimalBytes=${segMinimal.body.length} browserBytes=${segBrowser.body.length}"
                )
            } else {
                warn("FIRST_SEGMENT_MISSING", "Playlist was HLS but no media URI could be extracted")
            }
        } else {
            warn("FIRST_SEGMENT_SKIPPED", "No HLS playlist body was returned by either probe")
        }

        return browser.ok || minimal.ok
    }

    private suspend fun resolveStream(
        id: String,
        force: Boolean
    ): Pair<Endpoints, String>? {
        diag("RESOLVE_STREAM_BEGIN", "id=$id force=$force")
        val endpoints = resolveEndpoints(force) ?: run {
            warn("RESOLVE_STREAM_NO_ENDPOINT", "id=$id force=$force")
            return null
        }

        diag("DOMAIN_BEGIN", "GET ${endpoints.domainUrl} referer=${endpoints.site}/")
        val domainResponse = try {
            app.get(endpoints.domainUrl, referer = "${endpoints.site}/", cacheTime = 0)
        } catch (t: Throwable) {
            warn("DOMAIN_ERROR", "url=${endpoints.domainUrl}", t)
            return null
        }

        val domainText = domainResponse.text
        diag(
            "DOMAIN_HTTP",
            "url=${endpoints.domainUrl} code=${domainResponse.code} bytes=${domainText.length} diagnosis=${bodyDiagnosis(domainText)} body=${preview(domainText, 1200)}"
        )

        val parsed = domainResponse.parsedSafe<DomainResponse>()
        val domain = parsed?.baseurl?.trim()?.takeIf { it.isNotEmpty() }
        diag("DOMAIN_PARSED", "baseurl=${domain ?: "NULL"}")
        if (domain == null) {
            warn("DOMAIN_PARSE_FAIL", "domain.php did not provide a usable baseurl. raw=${preview(domainText, 1200)}")
            return null
        }

        val baseUrl = if (domain.endsWith("/")) domain else "$domain/"
        val streamUrl = "${baseUrl}${id}/mono.m3u8"
        diag("STREAM_URL_BUILT", "id=$id baseUrl=$baseUrl streamUrl=$streamUrl")

        val probeOk = runDeepStreamDiagnostics(streamUrl, endpoints.site)
        diag("STREAM_RESOLVE_RESULT", "id=$id probeOk=$probeOk streamUrl=$streamUrl")

        // Tanılama sürümünde probe başarısız olsa bile gerçek URL player'a gönderilir.
        // Böylece plugin HTTP sonucu ile Media3 HTTP sonucu aynı logda görülebilir.
        return endpoints to streamUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trace = System.currentTimeMillis().toString(16)
        diag("LOAD_LINKS_BEGIN", "trace=$trace data=$data casting=$isCasting")

        val id = Regex("""(?:\?|&)id=([^&]+)""").find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (id == null) {
            warn("LOAD_LINKS_ID_FAIL", "trace=$trace Could not extract channel id from data=$data")
            return false
        }
        diag("LOAD_LINKS_ID", "trace=$trace id=$id")

        var resolved = resolveStream(id, false)
        if (resolved == null) {
            warn("LOAD_LINKS_RETRY", "trace=$trace first resolve failed; clearing endpoint cache")
            cachedEndpoints = null
            resolved = resolveStream(id, true)
        }

        if (resolved == null) {
            warn("LOAD_LINKS_FAIL", "trace=$trace no stream URL could be constructed")
            return false
        }

        val (endpoints, streamUrl) = resolved
        val playerHeaders = browserHeaders(endpoints.site)

        diag(
            "CALLBACK_SEND",
            "trace=$trace url=$streamUrl type=M3U8 referer=${endpoints.site}/ headers=$playerHeaders"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "${endpoints.site}/"
                headers = playerHeaders
                quality = Qualities.Unknown.value
            }
        )

        diag("LOAD_LINKS_OK", "trace=$trace callbackCount=1 streamUrl=$streamUrl")
        return true
    }

}
