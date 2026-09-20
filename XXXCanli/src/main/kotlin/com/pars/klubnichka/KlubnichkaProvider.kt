package com.pars.klubnichka

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class KlubnichkaProvider : MainAPI() {
    override var mainUrl = "https://klubnichka.live"
    override var name = "Klubnichka"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ru"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Canlı Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document

        val items = doc.select("a.channel-card").mapNotNull { element ->
            val href = element.attr("href").trim()
            val title = element.selectFirst(".channel-card-title")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank {
                    element.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
                }
            val poster = element.selectFirst("img[src]")?.attr("src")?.trim().orEmpty()

            if (href.isBlank() || title.isBlank()) return@mapNotNull null

            newMovieSearchResponse(title, fixUrl(href), TvType.Live) {
                if (poster.isNotBlank()) posterUrl = fixUrl(poster)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val doc = app.get(mainUrl, headers = headers).document

        return doc.select("a.channel-card").mapNotNull { element ->
            val href = element.attr("href").trim()
            val title = element.selectFirst(".channel-card-title")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank {
                    element.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
                }
            val poster = element.selectFirst("img[src]")?.attr("src")?.trim().orEmpty()

            if (
                href.isBlank() ||
                title.isBlank() ||
                !title.lowercase().contains(q)
            ) return@mapNotNull null

            newMovieSearchResponse(title, fixUrl(href), TvType.Live) {
                if (poster.isNotBlank()) posterUrl = fixUrl(poster)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()?.substringBefore(" онлайн")?.trim()
            ?: "Canlı Yayın"

        val slug = runCatching {
            java.net.URI(url).path.trim('/').substringBefore("/")
        }.getOrNull().orEmpty()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            .orEmpty()
            .ifBlank {
                if (slug.isNotBlank()) "$mainUrl/images/$slug.png" else ""
            }

        return newMovieLoadResponse(title, url, TvType.Live, url) {
            if (poster.isNotBlank()) posterUrl = fixUrl(poster)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tag = "KLUB_RESOLVE"

        fun dbg(message: String) {
            println("$tag | $message")
        }

        dbg("BEGIN")
        dbg("DATA_URL=$data")

        return try {
            // AŞAMA 1: Kanal sayfası
            dbg("STEP1_CHANNEL_REQUEST=$data")
            val channelResponse = app.get(data, headers = headers)
            val channelHtml = channelResponse.text
            dbg("STEP1_CHANNEL_BODY_LENGTH=${channelHtml.length}")

            val channelDoc = Jsoup.parse(channelHtml, data)

            val channelIframes = channelDoc.select("iframe[src]")
            dbg("STEP1_IFRAME_COUNT=${channelIframes.size}")

            channelIframes.forEachIndexed { index, element ->
                dbg("STEP1_IFRAME[$index]=${element.absUrl("src")}")
            }

            val iframeUrl = channelIframes.firstOrNull()
                ?.absUrl("src")
                ?.trim()
                .orEmpty()

            dbg("STEP1_SELECTED_IFRAME=$iframeUrl")

            if (iframeUrl.isBlank()) {
                dbg("FAIL=CHANNEL_IFRAME_EMPTY")
                dbg("CHANNEL_HTML_HEAD=${channelHtml.take(800).replace("\n", " ")}")
                return false
            }

            // AŞAMA 2: /iframes/<kanal>.php
            dbg("STEP2_IFRAME_REQUEST=$iframeUrl")
            val iframeResponse = app.get(
                iframeUrl,
                headers = headers + mapOf("Referer" to data)
            )
            val iframeHtml = iframeResponse.text
            dbg("STEP2_IFRAME_BODY_LENGTH=${iframeHtml.length}")

            val iframeDoc = Jsoup.parse(iframeHtml, iframeUrl)
            val nestedIframes = iframeDoc.select("iframe[src]")
            dbg("STEP2_NESTED_IFRAME_COUNT=${nestedIframes.size}")

            nestedIframes.forEachIndexed { index, element ->
                dbg("STEP2_NESTED_IFRAME[$index]=${element.absUrl("src")}")
            }

            val playerUrl = nestedIframes.firstOrNull()
                ?.absUrl("src")
                ?.trim()
                .orEmpty()

            dbg("STEP2_PLAYER_URL=$playerUrl")

            if (playerUrl.isBlank()) {
                dbg("FAIL=PLAYER_IFRAME_EMPTY")
                dbg("IFRAME_HTML_HEAD=${iframeHtml.take(1200).replace("\n", " ")}")
                return false
            }

            // AŞAMA 3: /player/playerjs.php?ch=N
            dbg("STEP3_PLAYER_REQUEST=$playerUrl")
            val playerResponse = app.get(
                playerUrl,
                headers = headers + mapOf("Referer" to iframeUrl)
            )
            val playerHtml = playerResponse.text
            dbg("STEP3_PLAYER_BODY_LENGTH=${playerHtml.length}")
            dbg("STEP3_HAS_GENERATED_FILE=${playerHtml.contains("generatedFile", ignoreCase = true)}")
            dbg("STEP3_HAS_M3U8=${playerHtml.contains(".m3u8", ignoreCase = true)}")

            val generatedMatch = Regex(
                """generatedFile\s*=\s*["']([^"']+)["']""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(playerHtml)

            val generatedFile = generatedMatch
                ?.groups
                ?.get(1)
                ?.value
                ?.trim()
                .orEmpty()

            dbg("STEP3_GENERATED_FILE=$generatedFile")

            if (generatedFile.isBlank()) {
                dbg("FAIL=GENERATED_FILE_EMPTY")
                dbg("PLAYER_HTML_HEAD=${playerHtml.take(1800).replace("\n", " ")}")

                // Teşhis için HTML'de görünen bütün m3u8'leri de yaz.
                val rawM3u8 = Regex(
                    """https?://[^\s"'<>]+?\.m3u8(?:\?[^\s"'<>]*)?""",
                    RegexOption.IGNORE_CASE
                ).findAll(playerHtml)
                    .map { it.value }
                    .distinct()
                    .toList()

                dbg("PLAYER_HTML_M3U8_COUNT=${rawM3u8.size}")
                rawM3u8.forEachIndexed { index, url ->
                    dbg("PLAYER_HTML_M3U8[$index]=$url")
                }

                return false
            }

            // AŞAMA 4: generatedFile içinden gerçek HLS adresleri
            val streamUrls = Regex(
                """https?://[^\s"']+?\.m3u8(?:\?[^\s"']*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(generatedFile)
                .map { it.value.trim() }
                .distinct()
                .toList()

            dbg("STEP4_HLS_COUNT=${streamUrls.size}")
            streamUrls.forEachIndexed { index, url ->
                dbg("STEP4_HLS[$index]=$url")
            }

            if (streamUrls.isEmpty()) {
                dbg("FAIL=NO_HLS_IN_GENERATED_FILE")
                return false
            }

            // AŞAMA 5: CloudStream callback
            var emitted = 0

            streamUrls.forEachIndexed { index, streamUrl ->
                dbg("STEP5_EMIT_BEGIN[$index]=$streamUrl")

                callback(
                    newExtractorLink(
                        source = name,
                        name = if (index == 0) "$name • Canlı" else "$name • Yedek ${index + 1}",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "User-Agent" to headers.getValue("User-Agent")
                        )
                    }
                )

                emitted++
                dbg("STEP5_EMIT_OK[$index]")
            }

            dbg("DONE=TRUE EMITTED=$emitted")
            emitted > 0
        } catch (t: Throwable) {
            dbg("EXCEPTION_CLASS=${t.javaClass.name}")
            dbg("EXCEPTION_MESSAGE=${t.message}")
            dbg("EXCEPTION_STACK=${t.stackTraceToString().replace("\n", " | ")}")
            false
        }
    }

    private fun extractM3u8Candidates(body: String, baseUrl: String): List<String> {
        val decoded = body
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

        val regexes = listOf(
            Regex("https?://[^\\s\\\"'<>]+?\\.m3u8(?:\\?[^\\s\\\"'<>]*)?", RegexOption.IGNORE_CASE),
            Regex("[\\\"']([^\\\"']+?\\.m3u8(?:\\?[^\\\"']*)?)[\\\"']", RegexOption.IGNORE_CASE)
        )

        val out = LinkedHashSet<String>()

        regexes.forEach { regex ->
            regex.findAll(decoded).forEach { match ->
                val raw = (match.groups[1]?.value ?: match.value).trim()

                val fixed = when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> {
                        val uri = java.net.URI(baseUrl)
                        "${uri.scheme}://${uri.host}$raw"
                    }
                    else -> runCatching {
                        java.net.URI(baseUrl).resolve(raw).toString()
                    }.getOrDefault(raw)
                }

                if (fixed.startsWith("http")) out.add(fixed)
            }
        }

        return out.toList()
    }
}
