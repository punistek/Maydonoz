package com.keyiflerolsun

import android.util.Log
import android.util.Base64 as AndroidBase64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Sinemakolik : MainAPI() {
    override var mainUrl = "https://sinemakolik.com"
    override var name = "Sinemakolik"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    private fun headers(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Filmler",
        "$mainUrl/aile" to "Aile Filmleri",
        "$mainUrl/animasyon-izle" to "Animasyon Filmleri",
        "$mainUrl/aksiyon" to "Aksiyon Filmleri",
        "$mainUrl/bilim-kurgu" to "Bilim Kurgu Filmleri",
        "$mainUrl/korku" to "Korku Filmleri",
        "$mainUrl/savas" to "Savaş Filmleri",
        "$mainUrl/suc" to "Suç Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val url = if (page <= 1) {
            baseUrl
        } else {
            "$baseUrl/page/$page"
        }

        Log.i(
            "SNMK",
            "MAIN_PAGE GET section=${request.name} page=$page url=$url"
        )

        val document = app.get(url, headers = headers()).document
        val items = document.select("div.move_k a[href*=/film/]")
            .mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/film/")) return@mapNotNull null

                val title = a.attr("title").trim()
                    .ifBlank { a.selectFirst("h2, .title h2, .film-title")?.text()?.trim().orEmpty() }
                    .removeSuffix(" izle")
                    .trim()
                if (title.isBlank()) return@mapNotNull null

                val img = a.selectFirst("img")
                val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("srcset")?.substringBefore(" ")?.takeIf { it.startsWith("http") }
                    ?: img?.attr("src")?.takeIf { it.startsWith("http") }

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }

        Log.i("SNMK", "MAIN_PAGE DONE page=$page items=${items.size}")
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/arama/?s=$q"
        Log.i("SNMK", "SEARCH q=$query url=$url")

        val document = app.get(url, headers = headers()).document
        return document.select("a[href*=/film/]")
            .mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/film/")) return@mapNotNull null

                val title = a.attr("title").trim()
                    .ifBlank { a.selectFirst("h2, h3, .film-title, .h3baslik")?.text()?.trim().orEmpty() }
                    .removeSuffix(" izle")
                    .trim()
                if (title.isBlank()) return@mapNotNull null

                val img = a.selectFirst("img")
                val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")?.takeIf { it.startsWith("http") }

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i("SNMK", "LOAD url=$url")
        val document = app.get(url, headers = headers()).document

        val rawTitle = document.selectFirst("h1 [itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val title = rawTitle
            .replace(Regex("\\s+izle(?:\\s*-.*)?$", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("img[itemprop=image]")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.startsWith("http") }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = Regex("(?:19|20)\\d{2}")
            .find(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            ?.value?.toIntOrNull()
            ?: Regex("(?:19|20)\\d{2}")
                .find(document.select("#video_infos, .video_info").text())
                ?.value?.toIntOrNull()

        val trailer = document.selectFirst("meta[property=og:video]")?.attr("content")
            ?.takeIf { it.contains("youtube.com/embed/") || it.contains("youtu.be/") }

        Log.i("SNMK", "LOAD DONE title=$title year=$year poster=${!poster.isNullOrBlank()}")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    private data class SourceEmbed(
        val id: String,
        val label: String,
        val url: String
    )

    /**
     * Sinemakolik source encoding (verified from the page JS):
     *
     *   function rvali(s) => reverse(s)
     *   prefix = reverse("BSZtFmcmlGP") = "PGlmcmFtZSB"
     *   pdata['prt_<id>'] contains the remainder of a Base64 iframe.
     *
     * The initial player is also present as `ilkpartkod = '<base64>'`.
     */
    private fun decodeIframeBase64(raw: String, addPrefixWhenNeeded: Boolean): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        val prefix = "BSZtFmcmlGP".reversed()
        val encoded = if (
            addPrefixWhenNeeded &&
            !value.startsWith("PGltZyB3aWR0aD0iMTAwJSIgaGVpZ2") &&
            !value.startsWith("PGlmcmFtZ")
        ) {
            prefix + value
        } else {
            value
        }

        return runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun iframeUrl(decodedHtml: String): String? {
        val doc = Jsoup.parseBodyFragment(decodedHtml)
        val src = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() }
        if (src != null) return src

        return Regex(
            """https?://[^\"'\\s<>]+""",
            RegexOption.IGNORE_CASE
        ).find(decodedHtml)?.value
    }

    private fun extractSourceLabels(html: String): Map<String, String> {
        val document = Jsoup.parse(html)
        return document.select("li.psec[id]")
            .associate { li -> li.id() to li.text().trim() }
    }

    private fun extractVidMixiSources(html: String): List<SourceEmbed> {
        val out = linkedMapOf<String, SourceEmbed>()
        val labels = extractSourceLabels(html)

        // 1) Current/initial player. This is a complete Base64-encoded iframe.
        Regex(
            """ilkpartkod\s*=\s*['\"]([^'\"]+)['\"]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).forEachIndexed { index, match ->
            val decoded = decodeIframeBase64(match.groupValues[1], addPrefixWhenNeeded = false)
                ?: return@forEachIndexed
            val url = iframeUrl(decoded) ?: return@forEachIndexed
            if (url.contains("vidmixi.com/embed/", ignoreCase = true)) {
                out[url] = SourceEmbed("initial-$index", "SILVER HD", url)
            }
        }

        // 2) All player alternatives stored in pdata['prt_<id>'].
        Regex(
            """pdata\[['\"]prt_([^'\"]+)['\"]\]\s*=\s*['\"]([^'\"]+)['\"]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).forEach { match ->
            val id = match.groupValues[1].trim()
            if (id.contains("fragman", ignoreCase = true)) return@forEach

            val decoded = decodeIframeBase64(match.groupValues[2], addPrefixWhenNeeded = true)
                ?: return@forEach
            val url = iframeUrl(decoded) ?: return@forEach
            if (!url.contains("vidmixi.com/embed/", ignoreCase = true)) return@forEach

            out[url] = SourceEmbed(
                id = id,
                label = labels[id].orEmpty().ifBlank { "Kaynak $id" },
                url = url
            )
        }

        // 3) Safety fallback: if a future page prints a normal VidMixi URL directly.
        Regex(
            """https?://(?:www\.)?vidmixi\.com/embed/[^\"'\\s<]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEachIndexed { index, match ->
            val url = match.value.replace("\\/", "/")
            out.putIfAbsent(url, SourceEmbed("direct-$index", "VidMixi", url))
        }

        return out.values.toList()
    }


    // ---------------------------------------------------------------------
    // VIDMIXI / BEPLAYER - VIDEO + AUDIO + SUBTITLE
    // ---------------------------------------------------------------------

    private fun evpBytesToKey(
        password: ByteArray,
        salt: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val all = ArrayList<Byte>()
        var previous = ByteArray(0)

        while (all.size < 48) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()
            previous.forEach { all.add(it) }
        }

        val bytes = all.toByteArray()
        return bytes.copyOfRange(0, 32) to bytes.copyOfRange(32, 48)
    }

    private fun decryptBePlayer(
        password: String,
        rawJson: String
    ): JSONObject? {
        return runCatching {
            val json = JSONObject(
                rawJson
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")
            )

            val cipherText = AndroidBase64.decode(
                json.getString("ct"),
                AndroidBase64.DEFAULT
            )

            val salt = json.getString("s")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

            val (key, iv) = evpBytesToKey(
                password.toByteArray(Charsets.UTF_8),
                salt
            )

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )

            JSONObject(
                String(
                    cipher.doFinal(cipherText),
                    Charsets.UTF_8
                )
            )
        }.getOrNull()
    }

    private fun absoluteUrl(
        baseUrl: String,
        rawUrl: String
    ): String? {
        val raw = rawUrl
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (raw.isBlank()) return null

        return runCatching {
            URI(baseUrl).resolve(raw).toString()
        }.getOrElse {
            raw.takeIf {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            }
        }
    }

    private fun subtitleLabel(
        label: String?,
        language: String?,
        url: String
    ): String {
        val l = label?.trim().orEmpty()
        val langValue = language?.trim().orEmpty()

        if (l.isNotBlank()) {
            return when {
                l.equals("tr", true) ||
                    l.contains("tur", true) ||
                    l.contains("türk", true) -> "Türkçe"

                l.equals("en", true) ||
                    l.contains("eng", true) -> "English"

                l.contains("forced", true) -> "Forced"
                else -> l
            }
        }

        if (langValue.isNotBlank()) {
            return when {
                langValue.equals("tr", true) ||
                    langValue.contains("tur", true) -> "Türkçe"

                langValue.equals("en", true) ||
                    langValue.contains("eng", true) -> "English"

                langValue.contains("forced", true) -> "Forced"
                else -> langValue
            }
        }

        val lower = url.lowercase()
        return when {
            Regex("""(?:^|[_\-.])(tur|tr)(?:[_\-.]|$)""")
                .containsMatchIn(lower) -> "Türkçe"

            Regex("""(?:^|[_\-.])(eng|en)(?:[_\-.]|$)""")
                .containsMatchIn(lower) -> "English"

            lower.contains("forced") -> "Forced"
            else -> "Altyazı"
        }
    }

    private suspend fun emitMasterSubtitles(
        masterUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emittedSubtitleUrls: MutableSet<String>
    ) {
        val masterText = runCatching {
            app.get(
                masterUrl,
                headers = headers(referer),
                referer = referer
            ).text
        }.getOrNull() ?: return

        val attrRegex = Regex(
            """([A-Z0-9-]+)=("(?:[^"\\]|\\.)*"|[^,]*)""",
            RegexOption.IGNORE_CASE
        )

        masterText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) {
                return@forEach
            }

            val attrs = linkedMapOf<String, String>()
            attrRegex.findAll(line.substringAfter(":")).forEach { match ->
                val key = match.groupValues[1].uppercase()
                var value = match.groupValues[2].trim()
                if (
                    value.length >= 2 &&
                    value.startsWith("\"") &&
                    value.endsWith("\"")
                ) {
                    value = value.substring(1, value.length - 1)
                }
                attrs[key] = value
            }

            // AUDIO gruplarına dokunmuyoruz.
            // Master playlist'i aynen player'a verdiğimiz için Media3 seçebilir.
            if (!attrs["TYPE"].equals("SUBTITLES", ignoreCase = true)) {
                return@forEach
            }

            val rawUri = attrs["URI"].orEmpty()
            val subtitleUrl = absoluteUrl(masterUrl, rawUri) ?: return@forEach

            if (emittedSubtitleUrls.add(subtitleUrl)) {
                subtitleCallback(
                    SubtitleFile(
                        subtitleLabel(
                            attrs["NAME"],
                            attrs["LANGUAGE"],
                            subtitleUrl
                        ),
                        subtitleUrl
                    )
                )

                Log.i(
                    "SNMK",
                    "VIDMIXI HLS SUBTITLE " +
                        "label=${attrs["NAME"]} lang=${attrs["LANGUAGE"]} " +
                        "url=$subtitleUrl"
                )
            }
        }
    }

    private suspend fun resolveVidMixiBePlayer(
        source: SourceEmbed,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedText = runCatching {
            app.get(
                source.url,
                headers = headers(parentUrl),
                referer = parentUrl
            ).text
        }.getOrElse {
            Log.e(
                "SNMK",
                "VIDMIXI BEPLAYER embed failed " +
                    "${it::class.simpleName}: ${it.message}"
            )
            return false
        }

        val call = Regex(
            """bePlayer\s*\(\s*["']([^"']+)["']\s*,\s*["'](\{.*?\})["']""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        ).find(embedText) ?: return false

        val password = call.groupValues[1]
        val rawConfig = call.groupValues[2]
            .replace("\\'", "'")
            .replace("\\\"", "\"")

        val config = decryptBePlayer(
            password = password,
            rawJson = rawConfig
        ) ?: run {
            Log.e("SNMK", "VIDMIXI BEPLAYER decrypt failed source=${source.label}")
            return false
        }

        val mediaRaw = config.optString("video_location")
        val mediaUrl = absoluteUrl(source.url, mediaRaw)
            ?: return false

        val emittedSubtitleUrls = linkedSetOf<String>()

        val subtitles = config.optJSONArray("strSubtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(i) ?: continue
                val file = item.optString("file").trim()
                if (file.isBlank()) continue

                val subtitleUrl = absoluteUrl(
                    source.url,
                    file
                ) ?: continue

                if (!emittedSubtitleUrls.add(subtitleUrl)) continue

                val label = subtitleLabel(
                    item.optString("label"),
                    item.optString("language"),
                    subtitleUrl
                )

                subtitleCallback(
                    SubtitleFile(
                        label,
                        subtitleUrl
                    )
                )

                Log.i(
                    "SNMK",
                    "VIDMIXI SUBTITLE " +
                        "source=${source.label} label=$label url=$subtitleUrl"
                )
            }
        }

        // HLS master'da ayrıca SUBTITLES varsa onları da CloudStream'e ver.
        // AUDIO track'leri master URL korunarak Media3'e bırakılır.
        emitMasterSubtitles(
            masterUrl = mediaUrl,
            referer = source.url,
            subtitleCallback = subtitleCallback,
            emittedSubtitleUrls = emittedSubtitleUrls
        )

        callback(
            newExtractorLink(
                source = name,
                name = "${name} - ${source.label}",
                url = mediaUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = source.url
                this.quality = Qualities.Unknown.value
            }
        )

        Log.i(
            "SNMK",
            "VIDMIXI BEPLAYER OK source=${source.label} " +
                "subtitles=${emittedSubtitleUrls.size} media=$mediaUrl"
        )

        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("SNMK", "LOAD_LINKS START data=$data")

        val response = runCatching {
            app.get(data, headers = headers())
        }.getOrElse {
            Log.e("SNMK", "LOAD_LINKS detail failed ${it::class.simpleName}: ${it.message}")
            return false
        }

        val sources = extractVidMixiSources(response.text)
        Log.i(
            "SNMK",
            "LOAD_LINKS sources=${sources.size} " +
                sources.joinToString { "${it.label}:${it.url}" }
        )

        if (sources.isEmpty()) {
            Log.e("SNMK", "LOAD_LINKS no VidMixi source")
            return false
        }

        var emitted = 0

        for (source in sources) {
            var sourceEmitted = 0
            Log.i("SNMK", "VIDMIXI TRY id=${source.id} label=${source.label} url=${source.url}")

            val customResolved = runCatching {
                resolveVidMixiBePlayer(
                    source = source,
                    parentUrl = data,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        sourceEmitted++
                        emitted++
                        callback(link)
                    }
                )
            }.getOrElse {
                Log.e(
                    "SNMK",
                    "VIDMIXI BEPLAYER FAIL label=${source.label} " +
                        "${it::class.simpleName}: ${it.message}"
                )
                false
            }

            // BePlayer yapısı değişirse mevcut CloudStream extractor fallback'i korunuyor.
            if (!customResolved) {
                runCatching {
                    loadExtractor(
                        source.url,
                        data,
                        subtitleCallback
                    ) { link ->
                        sourceEmitted++
                        emitted++
                        Log.i(
                            "SNMK",
                            "VIDMIXI FALLBACK LINK label=${source.label} host=" +
                                runCatching { java.net.URI(link.url).host }.getOrNull() +
                                " path=" + runCatching { java.net.URI(link.url).path }.getOrNull()
                        )
                        callback(link)
                    }
                }.onFailure {
                    Log.e(
                        "SNMK",
                        "VIDMIXI FAIL label=${source.label} " +
                            "${it::class.simpleName}: ${it.message}"
                    )
                }
            }

            Log.i("SNMK", "VIDMIXI RESULT label=${source.label} links=$sourceEmitted")
        }

        Log.i("SNMK", "LOAD_LINKS DONE emitted=$emitted")
        return emitted > 0
    }
}
