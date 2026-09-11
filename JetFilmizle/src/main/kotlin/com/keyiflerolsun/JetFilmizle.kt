package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.UUID

class JetFilmizle : MainAPI() {

    override var mainUrl = "https://jetfilmizle.now"
    override var name = "JetFilmizle"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "/" to "Son Eklenen Filmler",
        "/tur/dram" to "Dram",
        "/tur/komedi" to "Komedi",
        "/tur/gerilim" to "Gerilim",
        "/tur/aksiyon" to "Aksiyon",
        "/tur/romantik" to "Romantik",
        "/tur/suc" to "Suç",
        "/tur/macera" to "Macera",
        "/tur/korku" to "Korku",
        "/tur/gizem" to "Gizem",
        "/tur/fantastik" to "Fantastik",
        "/tur/aile" to "Aile",
        "/tur/bilim-kurgu" to "Bilim Kurgu",
        "/tur/belgesel" to "Belgesel",
        "/tur/animasyon" to "Animasyon",
        "/tur/spor" to "Spor",
        "/tur/muzik" to "Müzik"
    )

    private val tag = "JET_RESOLVER"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val cloudflareInterceptor by lazy {
        JetCloudflareInterceptor(cloudflareKiller)
    }

    private class JetCloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)

            val body = try {
                response.peekBody(1024L * 1024L).string()
            } catch (_: Throwable) {
                ""
            }

            val challenged =
                body.contains("Just a moment", ignoreCase = true) ||
                    body.contains("cf-chl-", ignoreCase = true) ||
                    body.contains(
                        "/cdn-cgi/challenge-platform/",
                        ignoreCase = true
                    ) ||
                    body.contains(
                        "Enable JavaScript and cookies to continue",
                        ignoreCase = true
                    )

            if (challenged) {
                Log.w(
                    "JET_RESOLVER",
                    "Cloudflare challenge -> CloudflareKiller url=${request.url}"
                )
                response.close()
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val trace = traceId()
        val path = request.data

        val url = if (page <= 1) {
            fixUrl(path)
        } else {
            val sep = if (path.contains("?")) "&" else "?"
            fixUrl("$path${sep}page=$page")
        }

        Log.i(
            tag,
            "[$trace] MAIN_PAGE START page=$page request='${request.name}' url=$url"
        )

        return try {
            val response = app.get(
                url,
                headers = baseHeaders() + mapOf(
                    "Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cloudflareInterceptor
            )

            Log.i(
                tag,
                "[$trace] MAIN_PAGE GET status=${response.code} finalUrl=${response.url}"
            )

            val html = response.text
            Log.i(tag, "[$trace] MAIN_PAGE htmlLength=${html.length}")
            logHtmlState(trace, "MAIN_PAGE", html)

            if (isHardCloudflareBlock(html)) {
                Log.e(tag, "[$trace] MAIN_PAGE HARD CLOUDFLARE BLOCK")
                return newHomePageResponse(
                    request.name,
                    emptyList()
                )
            }

            val doc = Jsoup.parse(html, url)

            val candidates = linkedMapOf<String, SearchResponse>()

            // JetFilmizle film detay linkleri /film/... şeklinde.
            // Selector yapısını mümkün olduğunca genel tutuyoruz ama sadece film detaylarını alıyoruz.
            doc.select("a[href*=/film/]").forEachIndexed { index, a ->
                try {
                    val rawHref = a.attr("href").trim()
                    if (rawHref.isBlank()) return@forEachIndexed

                    val href = fixUrl(rawHref)

                    // Aynı film sayfada birden çok yerde geçebilir.
                    if (candidates.containsKey(href)) {
                        return@forEachIndexed
                    }

                    val title =
                        a.attr("title")
                            .trim()
                            .takeIf { it.isNotBlank() }
                            ?: a.selectFirst("img[alt]")
                                ?.attr("alt")
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                            ?: a.selectFirst(
                                ".film-title, .movie-title, .title, h2, h3, h4"
                            )
                                ?.text()
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                            ?: a.text()
                                .trim()
                                .takeIf { it.length in 2..180 }

                    if (title.isNullOrBlank()) {
                        return@forEachIndexed
                    }

                    val img = a.selectFirst("img")
                    val posterRaw =
                        listOf(
                            "data-src",
                            "data-lazy-src",
                            "data-original",
                            "src"
                        )
                            .firstNotNullOfOrNull { key ->
                                img?.attr(key)
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                            }

                    val poster = posterRaw?.let {
                        if (it.startsWith("http")) it else fixUrl(it)
                    }

                    val cleanTitle = title
                        .replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\s+filmi$""", RegexOption.IGNORE_CASE), "")
                        .trim()

                    if (cleanTitle.length < 2) {
                        return@forEachIndexed
                    }

                    val item = newMovieSearchResponse(
                        cleanTitle,
                        href,
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                    }

                    candidates[href] = item

                    if (index < 12) {
                        Log.d(
                            tag,
                            "[$trace] MAIN_PAGE ITEM index=$index title='$cleanTitle' href=$href posterPresent=${!poster.isNullOrBlank()}"
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(
                        tag,
                        "[$trace] MAIN_PAGE ITEM_PARSE_FAIL index=$index type=${t::class.java.simpleName} msg=${t.message}"
                    )
                }
            }

            val items = candidates.values.toList()

            Log.i(
                tag,
                "[$trace] MAIN_PAGE DONE uniqueItems=${items.size}"
            )

            if (items.isEmpty()) {
                Log.e(
                    tag,
                    "[$trace] MAIN_PAGE NO_ITEMS title='${doc.title()}'"
                )
            }

            newHomePageResponse(
                request.name,
                items
            )
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] MAIN_PAGE EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )

            newHomePageResponse(
                request.name,
                emptyList()
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val trace = traceId()

        Log.i(tag, "[$trace] LOAD START url=$url")

        val response = app.get(
            url,
            headers = baseHeaders() + mapOf(
                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "$mainUrl/"
            ),
            interceptor = cloudflareInterceptor
        )

        Log.i(
            tag,
            "[$trace] LOAD GET status=${response.code} finalUrl=${response.url}"
        )

        val html = response.text
        logHtmlState(trace, "LOAD", html)

        if (isHardCloudflareBlock(html)) {
            Log.e(tag, "[$trace] LOAD CLOUDFLARE HARD BLOCK")
            throw ErrorLoadingException("JetFilmizle Cloudflare blok")
        }

        val doc = Jsoup.parse(html, url)

        val title =
            doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: doc.title()
                    .substringBefore("|")
                    .trim()
                    .takeIf { it.isNotBlank() }
                ?: "JetFilmizle"

        val poster =
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val plot =
            doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val filmId =
            doc.selectFirst("input[name=film_id]")
                ?.attr("value")
                ?.trim()
                .orEmpty()

        Log.i(
            tag,
            "[$trace] LOAD parsed title='$title' filmId='$filmId' posterPresent=${!poster.isNullOrBlank()}"
        )

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trace = traceId()

        Log.i(tag, "[$trace] ========================================")
        Log.i(tag, "[$trace] V9 LOAD_LINKS START")
        Log.i(tag, "[$trace] DETAIL_URL=$data")
        Log.i(tag, "[$trace] isCasting=$isCasting")

        return try {
            val detailResponse = app.get(
                data,
                headers = baseHeaders() + mapOf(
                    "Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = cloudflareInterceptor
            )

            val detailHtml = detailResponse.text

            Log.i(
                tag,
                "[$trace] DETAIL status=${detailResponse.code} finalUrl=${detailResponse.url} htmlLength=${detailHtml.length}"
            )

            logHtmlState(trace, "DETAIL", detailHtml)

            if (isHardCloudflareBlock(detailHtml)) {
                Log.e(tag, "[$trace] DETAIL HARD_CLOUDFLARE_BLOCK")
                return false
            }

            val doc = Jsoup.parse(detailHtml, data)

            val filmId =
                doc.selectFirst("input[name=film_id]")
                    ?.attr("value")
                    ?.trim()
                    .orEmpty()

            Log.i(tag, "[$trace] FILM_ID='$filmId'")

            if (filmId.isBlank()) {
                Log.e(tag, "[$trace] FILM_ID BULUNAMADI")
                return false
            }

            val sources = discoverPlayerSources(
                trace = trace,
                doc = doc
            )

            Log.i(tag, "[$trace] SOURCE_COUNT=${sources.size}")

            if (sources.isEmpty()) {
                Log.e(
                    tag,
                    "[$trace] SOURCE YOK. Bu detail sayfasinda oynatilabilir kaynak tanimi bulunamadi."
                )
                return false
            }

            // Önce daha önce doğruladığımız OPlay ve JetGlobal kaynaklarını dene.
            // Sonra sayfadaki diğer gerçek kaynaklara geç.
            val orderedSources = sources.sortedWith(
                compareBy<PlayerSource> {
                    when {
                        // PARS player .m3u8 uzantılı kaynakları doğrudan Exo HLS açıyor.
                        // OPlay /m/... uzantısız HLS olduğu için player format fix gelene kadar
                        // Moly/Vidara'yı öne alıyoruz; OPlay yine güçlü fallback olarak kalıyor.
                        it.name.equals("Moly", ignoreCase = true) ||
                            it.name.equals("VidMoly", ignoreCase = true) -> 0
                        it.name.equals("Vidara", ignoreCase = true) -> 1
                        it.name.equals("OPlay", ignoreCase = true) -> 2
                        it.name.equals("StreamHLS", ignoreCase = true) -> 3
                        it.name.equals("JetGlobal", ignoreCase = true) -> 4
                        it.name.equals("Multi", ignoreCase = true) -> 5
                        // StreamTape CDN baglantisi logda 443 connect hatasi verdi;
                        // bu nedenle ancak son yedeklerden biri olsun.
                        it.name.equals("STape", ignoreCase = true) -> 9
                        else -> 6
                    }
                }.thenBy { it.playerType }
                    .thenBy { it.index.toIntOrNull() ?: Int.MAX_VALUE }
            )

            val visitedIframes = linkedSetOf<String>()

            for ((sourceOrdinal, source) in orderedSources.withIndex()) {
                Log.i(
                    tag,
                    "[$trace] SOURCE_TRY[$sourceOrdinal] name='${source.name}' type='${source.playerType}' index='${source.index}'"
                )

                val iframes = fetchJetPlayerIframes(
                    trace = trace,
                    detailUrl = data,
                    filmId = filmId,
                    source = source,
                    ordinal = sourceOrdinal
                )

                if (iframes.isEmpty()) {
                    Log.w(
                        tag,
                        "[$trace] SOURCE_TRY[$sourceOrdinal] iframe YOK"
                    )
                    continue
                }

                for ((iframeOrdinal, iframeUrl) in iframes.withIndex()) {
                    if (!visitedIframes.add(iframeUrl)) {
                        Log.d(
                            tag,
                            "[$trace] IFRAME_SKIP duplicate url=${safeUrlForLog(iframeUrl)}"
                        )
                        continue
                    }

                    val result = resolveRealIframe(
                        trace = trace,
                        iframeUrl = iframeUrl,
                        detailUrl = data,
                        source = source,
                        iframeOrdinal = iframeOrdinal,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (result) {
                        Log.i(
                            tag,
                            "[$trace] V9 FIRST_WORKING_SOURCE source='${source.name}' type='${source.playerType}' index='${source.index}'"
                        )
                        Log.i(
                            tag,
                            "[$trace] V9 LOAD_LINKS END emittedAny=true uniqueIframes=${visitedIframes.size}"
                        )
                        Log.i(tag, "[$trace] ========================================")
                        return true
                    }
                }
            }

            Log.i(
                tag,
                "[$trace] V9 LOAD_LINKS END emittedAny=false uniqueIframes=${visitedIframes.size}"
            )

            Log.i(tag, "[$trace] ========================================")

            false
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] V9 LOAD_LINKS EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            false
        }
    }

    private suspend fun fetchJetPlayerIframes(
        trace: String,
        detailUrl: String,
        filmId: String,
        source: PlayerSource,
        ordinal: Int
    ): List<String> {
        val jetPlayerUrl = "$mainUrl/jetplayer"

        Log.i(
            tag,
            "[$trace] JETPLAYER_REQ[$ordinal] filmId='$filmId' type='${source.playerType}' index='${source.index}' name='${source.name}'"
        )

        return try {
            val response = app.post(
                jetPlayerUrl,
                headers = baseHeaders() + mapOf(
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Referer" to detailUrl
                ),
                data = mapOf(
                    "film_id" to filmId,
                    "source_index" to source.index,
                    "player_type" to source.playerType
                ),
                interceptor = cloudflareInterceptor
            )

            val body = response.text

            Log.i(
                tag,
                "[$trace] JETPLAYER_RES[$ordinal] status=${response.code} finalUrl=${response.url} bodyLength=${body.length}"
            )

            logPreview(
                trace,
                "JETPLAYER_RES[$ordinal] PREVIEW",
                body
            )

            if (isHardCloudflareBlock(body)) {
                Log.e(
                    tag,
                    "[$trace] JETPLAYER_RES[$ordinal] HARD_CLOUDFLARE_BLOCK"
                )
                return emptyList()
            }

            val playerDoc = Jsoup.parse(body, jetPlayerUrl)

            val alerts = playerDoc.select(
                ".alert, .alert-danger, .error, .message, [role=alert]"
            )
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            alerts.forEachIndexed { alertIndex, alert ->
                Log.w(
                    tag,
                    "[$trace] JETPLAYER_ALERT[$ordinal][$alertIndex] '${safeTextForLog(alert, 500)}'"
                )
            }

            val result = playerDoc.select("iframe")
                .mapNotNull { iframe ->
                    val rawSrc = iframe.attr("src").trim()
                    val absSrc = iframe.absUrl("src").trim()
                    absSrc.ifBlank { rawSrc }.takeIf { it.isNotBlank() }
                }
                .distinct()

            result.forEachIndexed { iframeIndex, iframeUrl ->
                val host = hostOf(iframeUrl)

                Log.i(
                    tag,
                    "[$trace] JETPLAYER_IFRAME[$ordinal][$iframeIndex] host='$host' url='${safeUrlForLog(iframeUrl)}'"
                )
            }

            result
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] JETPLAYER ERROR source='${source.name}' type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            emptyList()
        }
    }

    private suspend fun resolveRealIframe(
        trace: String,
        iframeUrl: String,
        detailUrl: String,
        source: PlayerSource,
        iframeOrdinal: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = hostOf(iframeUrl).lowercase()

        Log.i(
            tag,
            "[$trace] RESOLVE_IFRAME[$iframeOrdinal] source='${source.name}' type='${source.playerType}' host='$host' url='${safeUrlForLog(iframeUrl)}'"
        )

        var emittedCount = 0

        val countingCallback: (ExtractorLink) -> Unit = { link ->
            emittedCount += 1
            Log.i(
                tag,
                "[$trace] REAL_LINK_CALLBACK[$emittedCount] source='${source.name}' host='$host' url='${safeUrlForLog(link.url)}'"
            )
            callback(link)
        }

        // OPlay VideoPark yolu daha önce doğrudan doğrulandı.
        if (host == "videopark.top" || host.endsWith(".videopark.top")) {
            val videoParkReported = VideoPark.resolve(
                embedUrl = iframeUrl,
                pageReferer = detailUrl,
                playerLabel = "${source.name}/${source.playerType}",
                trace = trace,
                subtitleCallback = subtitleCallback,
                callback = countingCallback
            )

            Log.i(
                tag,
                "[$trace] VIDEOPARK_RESULT reported=$videoParkReported emitted=$emittedCount source='${source.name}'"
            )

            // KRİTİK: resolver "true" dese bile callback gelmediyse başarılı sayma.
            if (emittedCount > 0) {
                Log.i(
                    tag,
                    "[$trace] RESOLVE_IFRAME VideoPark OK source='${source.name}' emitted=$emittedCount"
                )
                return true
            }

            Log.w(
                tag,
                "[$trace] RESOLVE_IFRAME VideoPark callback=0; generic extractor deneniyor."
            )
        }

        // VidMoly/Moly: CloudStream'in hazır extractor'ı bazı sayfalardaki
        // tek tırnaklı JWPlayer source dizisini JSON sanıp parse edemiyor.
        // Bu yüzden embed HTML içindeki gerçek master.m3u8'i kendimiz alıyoruz.
        if (
            host == "vidmoly.net" || host.endsWith(".vidmoly.net") ||
            host == "vidmoly.biz" || host.endsWith(".vidmoly.biz")
        ) {
            val beforeVidMoly = emittedCount
            val vidMolyReported = VidMoly.resolve(
                embedUrl = iframeUrl,
                pageReferer = detailUrl,
                playerLabel = "${source.name}/${source.playerType}",
                trace = trace,
                callback = countingCallback
            )
            val vidMolyEmitted = emittedCount - beforeVidMoly

            Log.i(
                tag,
                "[$trace] VIDMOLY_RESULT reported=$vidMolyReported emitted=$vidMolyEmitted source='${source.name}'"
            )

            if (vidMolyEmitted > 0) {
                return true
            }
        }

        val knownCollectedHost =
            host == "vidmoly.net" ||
                host.endsWith(".vidmoly.net") ||
                host == "vidmoly.biz" ||
                host.endsWith(".vidmoly.biz") ||
                host == "streamhls.to" ||
                host.endsWith(".streamhls.to") ||
                host == "player.abyssplayer.com" ||
                host.endsWith(".abyssplayer.com") ||
                host == "vidara.to" ||
                host.endsWith(".vidara.to") ||
                host == "streamtape.com" ||
                host.endsWith(".streamtape.com") ||
                host == "streamtape.to" ||
                host.endsWith(".streamtape.to") ||
                host == "ok.ru" ||
                host.endsWith(".ok.ru") ||
                host == "pixeldrain.com" ||
                host.endsWith(".pixeldrain.com") ||
                host == "videopark.top" ||
                host.endsWith(".videopark.top")

        if (!knownCollectedHost) {
            Log.w(
                tag,
                "[$trace] RESOLVE_IFRAME bilinmeyen host='$host'. Generic extractor bir kez deneniyor."
            )
        }

        return try {
            val before = emittedCount

            val reported = loadExtractor(
                iframeUrl,
                detailUrl,
                subtitleCallback,
                countingCallback
            )

            val genericEmitted = emittedCount - before

            Log.i(
                tag,
                "[$trace] GENERIC_EXTRACTOR host='$host' reported=$reported emitted=$genericEmitted source='${source.name}'"
            )

            // KRİTİK: loadExtractor true dönebilir ama 0 link callback verebilir.
            genericEmitted > 0
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] GENERIC_EXTRACTOR FAIL host='$host' type=${t::class.java.simpleName} msg=${t.message}"
            )
            false
        }
    }


    private fun hostOf(url: String): String {
        return try {
            java.net.URI(url).host.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }


    private fun dumpPlayerArea(
        trace: String,
        doc: org.jsoup.nodes.Document
    ) {
        val selectors = listOf(
            "[data-source-index]",
            "[data-player-type]",
            ".player-source-btn",
            ".player-source",
            ".player-sources",
            ".source-btn",
            ".sources",
            "#player-sources",
            "#source-list"
        )

        selectors.forEach { selector ->
            val matches = doc.select(selector)
            Log.d(
                tag,
                "[$trace] PLAYER_AREA selector='$selector' count=${matches.size}"
            )

            matches.take(25).forEachIndexed { i, el ->
                Log.d(
                    tag,
                    "[$trace] PLAYER_AREA_HTML selector='$selector' i=$i ${safeTextForLog(el.outerHtml(), 1200)}"
                )
            }
        }

        doc.select("script").forEachIndexed { i, script ->
            val scriptBody = script.data().ifBlank { script.html() }

            if (
                scriptBody.contains("jetplayer", ignoreCase = true) ||
                scriptBody.contains("source_index", ignoreCase = true) ||
                scriptBody.contains("data-source-index", ignoreCase = true) ||
                scriptBody.contains("player_type", ignoreCase = true)
            ) {
                Log.d(
                    tag,
                    "[$trace] PLAYER_SCRIPT[$i] ${safeTextForLog(scriptBody, 1800)}"
                )
            }
        }
    }


    private data class PlayerSource(
        val name: String,
        val index: String,
        val playerType: String,
        val raw: String
    )

    /**
     * Kaynakları tek bir CSS class adına bağlamıyoruz.
     * Site class adını değiştirse bile data-source-index / data-player-type
     * taşıyan elemanları ve yakın ebeveynlerini tarıyoruz.
     *
     * Önemli: source_index artık ASLA sabit 1 kabul edilmiyor.
     */
    private fun discoverPlayerSources(
        trace: String,
        doc: org.jsoup.nodes.Document
    ): List<PlayerSource> {
        val found = linkedMapOf<String, PlayerSource>()

        val elements = doc.select("[data-source-index]")

        Log.i(
            tag,
            "[$trace] DISCOVERY data-source-index count=${elements.size}"
        )

        elements.forEachIndexed { i, el ->
            val index = el.attr("data-source-index").trim()

            val playerType =
                attrFromSelfOrParents(el, "data-player-type")
                    .ifBlank {
                        attrFromSelfOrParents(el, "data-type")
                    }

            val nameCandidates = listOf(
                el.attr("data-source-name"),
                el.attr("data-name"),
                el.attr("data-provider"),
                el.attr("title"),
                el.attr("aria-label"),
                el.selectFirst(".name, .title, span")?.text().orEmpty(),
                el.text(),
                el.parent()?.text().orEmpty()
            )

            val name = nameCandidates
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .ifBlank { "Unknown" }

            val raw = el.outerHtml()

            Log.i(
                tag,
                "[$trace] SOURCE_RAW[$i] index='$index' type='$playerType' name='${safeTextForLog(name, 200)}'"
            )
            Log.d(
                tag,
                "[$trace] SOURCE_HTML[$i] ${safeTextForLog(raw, 1400)}"
            )

            if (index.isBlank()) {
                Log.w(
                    tag,
                    "[$trace] SOURCE_SKIP[$i] reason=blank_index"
                )
                return@forEachIndexed
            }

            if (playerType.isBlank()) {
                Log.w(
                    tag,
                    "[$trace] SOURCE_SKIP[$i] reason=blank_player_type"
                )
                return@forEachIndexed
            }

            val key = "$playerType|$index"

            found.putIfAbsent(
                key,
                PlayerSource(
                    name = name,
                    index = index,
                    playerType = playerType,
                    raw = raw
                )
            )
        }

        found.values.forEachIndexed { i, source ->
            Log.i(
                tag,
                "[$trace] SOURCE[$i] name='${safeTextForLog(source.name, 180)}' type='${source.playerType}' index='${source.index}'"
            )
        }

        return found.values.toList()
    }

    private fun attrFromSelfOrParents(
        element: org.jsoup.nodes.Element,
        attr: String
    ): String {
        var current: org.jsoup.nodes.Element? = element
        var depth = 0

        while (current != null && depth < 6) {
            val value = current.attr(attr).trim()

            if (value.isNotBlank()) {
                return value
            }

            current = current.parent()
            depth++
        }

        return ""
    }

    private fun safeTextForLog(
        text: String,
        max: Int
    ): String {
        val cleaned = text
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return if (cleaned.length > max) {
            cleaned.take(max) + "...[len=${cleaned.length}]"
        } else {
            cleaned
        }
    }

    private fun isHardCloudflareBlock(html: String): Boolean {
        return html.contains(
            "Sorry, you have been blocked",
            ignoreCase = true
        ) ||
            html.contains(
                "Attention Required! | Cloudflare",
                ignoreCase = true
            ) ||
            html.contains(
                "cf-error-details",
                ignoreCase = true
            )
    }

    private fun logHtmlState(
        trace: String,
        stage: String,
        html: String
    ) {
        Log.d(
            tag,
            "[$trace] $stage flags " +
                "justMoment=${html.contains("Just a moment", true)} " +
                "hardBlock=${isHardCloudflareBlock(html)} " +
                "filmId=${html.contains("name=\"film_id\"", true) || html.contains("name='film_id'", true)}"
        )

        logPreview(trace, "$stage PREVIEW", html)
    }

    private fun logPreview(
        trace: String,
        title: String,
        text: String,
        max: Int = 900
    ) {
        val cleaned = text
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val preview =
            if (cleaned.length > max) {
                cleaned.take(max) + "...[len=${cleaned.length}]"
            } else {
                cleaned
            }

        Log.d(tag, "[$trace] $title=$preview")
    }

    private fun safeUrlForLog(url: String): String {
        if (url.isBlank()) return "<empty>"
        if (url.length <= 180) return url

        return url.take(110) +
            "...[len=${url.length}]..." +
            url.takeLast(35)
    }

    private fun traceId(): String =
        UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(8)
}
