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
        // 1) Kanal sayfası -> /iframes/<kanal>.php
        val channelDoc = app.get(data, headers = headers).document
        val iframeUrl = channelDoc.selectFirst("iframe[src]")
            ?.absUrl("src")
            ?.trim()
            .orEmpty()

        if (iframeUrl.isBlank()) return false

        // 2) /iframes/<kanal>.php -> /player/playerjs.php?ch=N
        val iframeDoc = app.get(
            iframeUrl,
            headers = headers + mapOf("Referer" to data)
        ).document

        val playerUrl = iframeDoc.selectFirst("iframe[src]")
            ?.absUrl("src")
            ?.trim()
            .orEmpty()

        if (playerUrl.isBlank()) return false

        // 3) playerjs.php cevabında generatedFile doğrudan tokenli HLS'leri veriyor.
        val playerHtml = app.get(
            playerUrl,
            headers = headers + mapOf("Referer" to iframeUrl)
        ).text

        val generatedFile = Regex(
            """var\s+generatedFile\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(playerHtml)?.groups?.get(1)?.value.orEmpty()

        if (generatedFile.isBlank()) return false

        // Örnek:
        // https://tvcdnpotok.com/6/index.m3u8?... or https://tvlife.live/6/index.m3u8?...
        val streamUrls = Regex(
            """https?://[^\s"']+?\.m3u8(?:\?[^\s"']*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(generatedFile)
            .map { it.value.trim() }
            .distinct()
            .toList()

        if (streamUrls.isEmpty()) return false

        streamUrls.forEachIndexed { index, streamUrl ->
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
        }

        return true
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
