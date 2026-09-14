package com.pars.xhamster

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class XHamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "XHamster"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/4k" to "4K",
        "$mainUrl/categories/18-year-old" to "18 Year Old",
        "$mainUrl/categories/amateur" to "Amateur",
    )

    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val html = app.get(url, headers = browserHeaders, referer = "$mainUrl/").text
        val initials = extractInitials(html)
        val items = parseVideoCards(initials)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val url = "$mainUrl/search/$encoded"
        val html = app.get(url, headers = browserHeaders, referer = "$mainUrl/").text
        return parseVideoCards(extractInitials(html))
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = browserHeaders, referer = "$mainUrl/").text
        val initials = extractInitials(html) ?: return null
        val video = initials.optJSONObject("videoModel") ?: return null

        val title = video.optString("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = firstNonBlank(
            video.optString("imageURL"),
            video.optString("thumbURL"),
            video.optString("previewThumbURL"),
        )
        val description = video.optString("description").trim().takeIf { it.isNotBlank() }
        val tags = jsonObjectNames(video.optJSONArray("categories"))

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data, headers = browserHeaders, referer = "$mainUrl/").text
        val initials = extractInitials(html) ?: return false
        val xplayerSources = initials
            .optJSONObject("xplayerSettings")
            ?.optJSONObject("sources")
            ?: return false

        val emitted = linkedSetOf<String>()

        val hls = xplayerSources.optJSONObject("hls")
        if (hls != null) {
            for (key in listOf("url", "fallback")) {
                val raw = hls.optString(key).trim()
                if (raw.isBlank()) continue
                val finalUrl = decipherFormatUrl(raw) ?: continue
                if (!finalUrl.startsWith("http") || !emitted.add(finalUrl)) continue
                emitHls(finalUrl, data, callback)
            }
        }

        // Bazı videolarda HLS, standard listesi içinde de gelebiliyor.
        val standard = xplayerSources.optJSONObject("standard")
        if (standard != null) {
            val names = standard.keys()
            while (names.hasNext()) {
                val groupName = names.next()
                val formats = standard.optJSONArray(groupName) ?: continue
                for (i in 0 until formats.length()) {
                    val item = formats.optJSONObject(i) ?: continue
                    for (key in listOf("url", "fallback")) {
                        val raw = item.optString(key).trim()
                        if (raw.isBlank()) continue
                        val finalUrl = decipherFormatUrl(raw) ?: continue
                        if (!finalUrl.startsWith("http") || !finalUrl.contains(".m3u8", ignoreCase = true)) continue
                        if (!emitted.add(finalUrl)) continue
                        emitHls(finalUrl, data, callback)
                    }
                }
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun emitHls(
        url: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                source = "XHamster",
                name = "XHamster HLS",
                url = url,
                type = ExtractorLinkType.M3U8,
            ) {
                referer = refererUrl
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to refererUrl,
                    "User-Agent" to browserHeaders.getValue("User-Agent"),
                )
            }
        )
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base + if (base.contains("?")) "&page=$page" else "?page=$page"
    }

    private fun parseVideoCards(initials: JSONObject?): List<SearchResponse> {
        if (initials == null) return emptyList()
        val found = linkedMapOf<String, SearchResponse>()
        collectVideoObjects(initials).forEach { video ->
            val title = video.optString("title").trim().takeIf { it.isNotBlank() }
                ?: return@forEach
            val url = video.optString("pageURL").trim().takeIf {
                it.startsWith("http") && it.contains("/videos/")
            } ?: return@forEach
            val poster = firstNonBlank(
                video.optString("thumbURL"),
                video.optString("imageURL"),
                video.optString("previewThumbURL"),
            )

            found[url] = newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
        return found.values.toList()
    }

    private fun collectVideoObjects(root: Any?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val pageUrl = node.optString("pageURL")
                    val title = node.optString("title")
                    if (pageUrl.contains("/videos/") && title.isNotBlank()) {
                        out += node
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
        }

        walk(root)
        return out
    }

    private fun extractInitials(html: String): JSONObject? {
        val marker = "window.initials"
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null

        val equalIndex = html.indexOf('=', markerIndex + marker.length)
        if (equalIndex < 0) return null

        val start = html.indexOf('{', equalIndex + 1)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            JSONObject(html.substring(start, i + 1))
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun jsonObjectNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> item.optString("name").trim().takeIf { it.isNotBlank() }?.let(out::add)
                is String -> item.trim().takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out.distinct()
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() }

    private fun decipherFormatUrl(formatUrl: String): String? {
        val value = formatUrl.trim()
        if (value.isBlank()) return null

        if (VALID_HEX.matches(value)) {
            return decipherHexString(value)
        }

        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val path = uri.rawPath ?: return value
        val match = ENCRYPTED_PATH.find(path)

        // Site bazı oturumlarda URL'yi zaten açık/signed olarak döndürüyor.
        if (match == null) return value

        val hex = match.groupValues[1]
        val remainder = match.groupValues[2]
        val decoded = decipherHexString(hex) ?: return null
        val newPath = "/$decoded$remainder"

        return runCatching {
            URI(
                uri.scheme,
                uri.rawAuthority,
                newPath,
                uri.rawQuery,
                uri.rawFragment,
            ).toASCIIString()
        }.getOrNull()
    }

    private fun decipherHexString(hex: String): String? {
        val bytes = hexToBytes(hex) ?: return null
        if (bytes.size < 6) return null

        val algoId = bytes[0].toInt() and 0xFF
        val seed =
            (bytes[1].toInt() and 0xFF) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            ((bytes[3].toInt() and 0xFF) shl 16) or
            ((bytes[4].toInt() and 0xFF) shl 24)

        val generator = ByteGenerator(algoId, seed) ?: return null
        val chars = CharArray(bytes.size - 5)

        for (i in 5 until bytes.size) {
            val decoded = (bytes[i].toInt() and 0xFF) xor generator.nextByte()
            chars[i - 5] = decoded.toChar()
        }
        return String(chars)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private class ByteGenerator private constructor(
        private val algoId: Int,
        private var state: Int,
    ) {
        fun nextByte(): Int {
            val output = when (algoId) {
                1 -> algo1()
                2 -> algo2()
                3 -> algo3()
                4 -> algo4()
                5 -> algo5()
                6 -> algo6()
                7 -> algo7()
                else -> return 0
            }
            return output and 0xFF
        }

        private fun algo1(): Int {
            state = state * 1_664_525 + 1_013_904_223
            return state
        }

        private fun algo2(): Int {
            var s = state
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            state = s
            return s
        }

        private fun algo3(): Int {
            state += 0x9E3779B9L.toInt()
            var s = state
            s = s xor (s ushr 16)
            s *= 0x85EBCA77L.toInt()
            s = s xor (s ushr 13)
            s *= 0xC2B2AE3DL.toInt()
            return s xor (s ushr 16)
        }

        private fun algo4(): Int {
            state += 0x6D2B79F5
            var s = state
            s = (s shl 7) or (s ushr 25)
            s += 0x9E3779B9L.toInt()
            s = s xor (s ushr 11)
            return s * 0x27D4EB2D
        }

        private fun algo5(): Int {
            var s = state
            s = s xor (s shl 7)
            s = s xor (s ushr 9)
            s = s xor (s shl 8)
            s += 0xA5A5A5A5L.toInt()
            state = s
            return s
        }

        private fun algo6(): Int {
            state = state * 0x2C9277B5 + 0xAC564B05L.toInt()
            val s2 = state xor (state ushr 18)
            val shift = (state ushr 27) and 31
            return s2 ushr shift
        }

        private fun algo7(): Int {
            state += 0x9E3779B9L.toInt()
            var e = state xor (state shl 5)
            e *= 0x7FEB352D
            e = e xor (e ushr 15)
            return e * 0x846CA68BL.toInt()
        }

        companion object {
            operator fun invoke(algoId: Int, seed: Int): ByteGenerator? =
                if (algoId in 1..7) ByteGenerator(algoId, seed) else null
        }
    }

    companion object {
        private val VALID_HEX = Regex("^[0-9a-fA-F]{12,}$")
        private val ENCRYPTED_PATH = Regex("^/([0-9a-fA-F]{12,})([/,].+)$")
    }
}
