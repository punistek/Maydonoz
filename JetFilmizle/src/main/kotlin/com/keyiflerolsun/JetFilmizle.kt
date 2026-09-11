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
        "/" to "Son Eklenen Filmler"
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
        Log.i(tag, "[$trace] LOAD_LINKS START")
        Log.i(tag, "[$trace] data=$data")
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

            Log.i(
                tag,
                "[$trace] [1/6] DETAIL status=${detailResponse.code} finalUrl=${detailResponse.url}"
            )

            val detailHtml = detailResponse.text
            Log.i(tag, "[$trace] [1/6] DETAIL htmlLength=${detailHtml.length}")
            logHtmlState(trace, "DETAIL", detailHtml)

            if (isHardCloudflareBlock(detailHtml)) {
                Log.e(tag, "[$trace] [1/6] HARD CLOUDFLARE BLOCK")
                return false
            }

            val doc = Jsoup.parse(detailHtml, data)

            val filmId =
                doc.selectFirst("input[name=film_id]")
                    ?.attr("value")
                    ?.trim()
                    .orEmpty()

            Log.i(tag, "[$trace] [2/6] filmId='$filmId'")

            if (filmId.isBlank()) {
                Log.e(tag, "[$trace] [2/6] film_id BULUNAMADI")
                return false
            }

            val sources = discoverPlayerSources(
                trace = trace,
                doc = doc
            )

            Log.i(
                tag,
                "[$trace] [3/6] discoveredSources=${sources.size}"
            )

            if (sources.isEmpty()) {
                Log.e(
                    tag,
                    "[$trace] [3/6] HICBIR PLAYER SOURCE BULUNAMADI - sabit index fallback KULLANILMADI"
                )
                return false
            }

            // Önce OPlay. OPlay yoksa şu an bilmediğimiz resolver'a körlemesine
            // gitmiyoruz; logda gerçek kaynakları görüyoruz ve sonraki resolver'ı
            // kanıtla ekliyoruz.
            val oplaySources = sources.filter {
                it.name.contains("oplay", ignoreCase = true) ||
                    it.name.contains("o play", ignoreCase = true) ||
                    it.raw.contains("/oplayer/", ignoreCase = true)
            }

            Log.i(
                tag,
                "[$trace] [3/6] oplaySources=${oplaySources.size}"
            )

            if (oplaySources.isEmpty()) {
                Log.e(
                    tag,
                    "[$trace] [3/6] BU FILMDE OPLAY BULUNAMADI. Mevcut kaynaklar=${sources.joinToString { "${it.name}[${it.playerType}:${it.index}]" }}"
                )
                return false
            }

            var emittedAny = false

            oplaySources.forEach { source ->
                Log.i(
                    tag,
                    "[$trace] [3/6] OPLAY SECILDI name='${source.name}' type=${source.playerType} index=${source.index}"
                )

                val emitted = resolveOPlay(
                    trace = trace,
                    detailUrl = data,
                    filmId = filmId,
                    sourceIndex = source.index,
                    playerType = source.playerType,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                emittedAny = emittedAny || emitted
            }

            Log.i(
                tag,
                "[$trace] LOAD_LINKS END emittedAny=$emittedAny"
            )
            Log.i(tag, "[$trace] ========================================")

            emittedAny
        } catch (t: Throwable) {
            Log.e(
                tag,
                "[$trace] LOAD_LINKS EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            false
        }
    }

    private suspend fun resolveOPlay(
        trace: String,
        detailUrl: String,
        filmId: String,
        sourceIndex: String,
        playerType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val jetPlayerUrl = "$mainUrl/jetplayer"

        Log.i(
            tag,
            "[$trace] [4/6] JETPLAYER POST type=$playerType filmId=$filmId sourceIndex=$sourceIndex"
        )

        val postResponse = app.post(
            jetPlayerUrl,
            headers = baseHeaders() + mapOf(
                "Accept" to "*/*",
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to detailUrl
            ),
            data = mapOf(
                "film_id" to filmId,
                "source_index" to sourceIndex,
                "player_type" to playerType
            ),
            interceptor = cloudflareInterceptor
        )

        Log.i(
            tag,
            "[$trace] [4/6] JETPLAYER status=${postResponse.code} finalUrl=${postResponse.url}"
        )

        val playerCode = postResponse.text

        Log.i(
            tag,
            "[$trace] [4/6] JETPLAYER bodyLength=${playerCode.length}"
        )

        logPreview(
            trace,
            "[4/6] JETPLAYER PREVIEW",
            playerCode
        )

        if (isHardCloudflareBlock(playerCode)) {
            Log.e(
                tag,
                "[$trace] [4/6] JETPLAYER HARD CLOUDFLARE BLOCK"
            )
            return false
        }

        val playerDoc = Jsoup.parse(playerCode, jetPlayerUrl)

        val iframeUrl =
            playerDoc.selectFirst("iframe[src]")
                ?.let { iframe ->
                    iframe.absUrl("src")
                        .takeIf { it.isNotBlank() }
                        ?: iframe.attr("src")
                            .trim()
                            .takeIf { it.isNotBlank() }
                }
                .orEmpty()

        Log.i(
            tag,
            "[$trace] [5/6] iframeUrl=${safeUrlForLog(iframeUrl)}"
        )

        if (iframeUrl.isBlank()) {
            Log.e(tag, "[$trace] [5/6] iframe BULUNAMADI")
            return false
        }

        if (!iframeUrl.contains("videopark.top", ignoreCase = true)) {
            Log.e(
                tag,
                "[$trace] [5/6] Beklenmeyen iframe host=$iframeUrl"
            )
            return false
        }

        Log.i(
            tag,
            "[$trace] [6/6] VideoPark resolve başlıyor type=$playerType"
        )

        val result = VideoPark.resolve(
            embedUrl = iframeUrl,
            pageReferer = detailUrl,
            playerLabel = playerType,
            trace = trace,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        Log.i(
            tag,
            "[$trace] [6/6] VideoPark result=$result type=$playerType"
        )

        return result
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

        val selectors = listOf(
            "[data-source-index]",
            "[data-player-type][data-source-index]",
            ".player-source-btn",
            "button[data-source-index]",
            "a[data-source-index]"
        )

        selectors.forEach { selector ->
            val elements = doc.select(selector)

            Log.d(
                tag,
                "[$trace] SOURCE_SCAN selector='$selector' count=${elements.size}"
            )

            elements.forEachIndexed { i, el ->
                val index = el.attr("data-source-index").trim()
                    .ifBlank {
                        el.parent()?.attr("data-source-index")?.trim().orEmpty()
                    }

                val playerType = el.attr("data-player-type").trim()
                    .ifBlank {
                        el.parent()?.attr("data-player-type")?.trim().orEmpty()
                    }

                val textCandidates = listOf(
                    el.attr("data-source-name"),
                    el.attr("data-name"),
                    el.attr("title"),
                    el.attr("aria-label"),
                    el.text(),
                    el.parent()?.text().orEmpty()
                )

                val name = textCandidates
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()

                val raw = buildString {
                    append(el.outerHtml())
                    append(" ")
                    append(el.parent()?.outerHtml().orEmpty())
                }

                Log.d(
                    tag,
                    "[$trace] SOURCE_RAW selector='$selector' i=$i index='$index' type='$playerType' name='$name' html=${safeTextForLog(raw, 650)}"
                )

                if (index.isBlank() || playerType.isBlank()) {
                    return@forEachIndexed
                }

                val normalizedName = when {
                    name.contains("oplay", true) -> "OPlay"
                    name.contains("o play", true) -> "OPlay"
                    raw.contains("/oplayer/", true) -> "OPlay"
                    name.contains("vip", true) -> "Vip"
                    name.contains("okru", true) ||
                        name.contains("ok.ru", true) -> "OkRu"
                    name.contains("stape", true) ||
                        name.contains("streamtape", true) -> "STape"
                    name.contains("streamhls", true) ||
                        name.contains("stream hls", true) -> "StreamHLS"
                    else -> name.ifBlank { "Unknown" }
                }

                val key = "$playerType|$index|$normalizedName"

                found.putIfAbsent(
                    key,
                    PlayerSource(
                        name = normalizedName,
                        index = index,
                        playerType = playerType,
                        raw = raw
                    )
                )
            }
        }

        // Bazı sürümlerde buton bilgileri HTML elementinde değil script/string içinde
        // olabilir. Bu durumda kanıt amaçlı ilgili satırları logla; uydurma index üretme.
        if (found.isEmpty()) {
            doc.select("script").forEachIndexed { i, script ->
                val body = script.data().ifBlank { script.html() }

                if (
                    body.contains("source_index", true) ||
                    body.contains("data-source-index", true) ||
                    body.contains("oplay", true)
                ) {
                    Log.w(
                        tag,
                        "[$trace] SOURCE_SCRIPT_HINT i=$i ${safeTextForLog(body, 1000)}"
                    )
                }
            }
        }

        found.values.forEachIndexed { i, source ->
            Log.i(
                tag,
                "[$trace] SOURCE[$i] name='${source.name}' type='${source.playerType}' index='${source.index}'"
            )
        }

        return found.values.toList()
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
