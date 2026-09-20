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
            // 1) Kanal sayfası normal HTTP ile erişilebilir.
            val channelResponse = app.get(data, headers = headers)
            val channelHtml = channelResponse.text
            val channelDoc = Jsoup.parse(channelHtml, data)

            val iframeUrl = channelDoc.selectFirst("iframe[src]")
                ?.absUrl("src")
                ?.trim()
                .orEmpty()

            dbg("CHANNEL_HTTP=${channelResponse.code}")
            dbg("IFRAME_URL=$iframeUrl")

            if (iframeUrl.isBlank()) {
                dbg("FAIL=CHANNEL_IFRAME_EMPTY")
                return false
            }

            /*
             * Resolver Lab kanıtı:
             *   normal HTTP -> /iframes/<slug>.php = 403
             *   gerçek Chrome -> iframe çalışıyor
             *   Chrome network -> /player/playerjs.php?ch=N = 200
             *
             * Bu nedenle burada 403 veren iframe'i tekrar app.get() ile zorlamıyoruz.
             *
             * ÖNEMLİ:
             * Kanal slug -> ch numarası kanal HTML'inde bulunmuyor.
             * Pinko için ch=22 görülmesi yalnız Pinko'yu kanıtlar; diğer kanallara
             * aynı/hesaplanmış numarayı vermek yanlış olur.
             *
             * PARS runtime bu marker'ı görünce mevcut browser-runtime çözümüne
             * handoff etmelidir. Böylece gerçek Chrome/WebView network'ünden
             * playerjs.php ve dinamik m3u8 yakalanır.
             */
            dbg("BROWSER_REQUIRED=TRUE")
            dbg("BROWSER_TARGET=$data")
            dbg("BROWSER_IFRAME=$iframeUrl")
            dbg("FAIL=STATIC_HTTP_BLOCKED_BY_IFRAME_403")

            // PARS tarafındaki browser handoff marker'ı.
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • Browser Runtime",
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "X-PARS-WEBVIEW" to "1",
                        "X-PARS-WEBVIEW-TARGET" to data,
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to headers.getValue("User-Agent")
                    )
                }
            )

            dbg("DONE=HANDOFF_EMITTED")
            true
        } catch (t: Throwable) {
            dbg("EXCEPTION_CLASS=${t.javaClass.name}")
            dbg("EXCEPTION_MESSAGE=${t.message}")
            dbg("EXCEPTION_STACK=${t.stackTrace.joinToString(" | ")}")
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
