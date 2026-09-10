package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class FullHDFilmizle : MainAPI() {
    override var mainUrl = "https://fullhdfilmizle.now"
    override var name = "FullHDFilmizle"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/" to "Filmler",
        "/yabanci-dizi-izle" to "Diziler",
        "/tur/aksiyon" to "Aksiyon",
        "/tur/dram" to "Dram",
        "/tur/komedi" to "Komedi",
        "/tur/korku" to "Korku",
        "/tur/macera" to "Macera",
        "/tur/bilim-kurgu" to "Bilim Kurgu"
    )

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = selectFirst("a.mc-link")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".film-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a.mc-link")?.attr("title")?.removeSuffix(" izle")?.trim()
            ?: return null
        val poster = selectFirst("img.mc-afis")?.let { img ->
            listOf("data-src", "data-original", "src").firstNotNullOfOrNull { key ->
                img.attr(key).takeIf { it.isNotBlank() }
            }
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster?.let(::fixUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (page <= 1) fixUrl(path) else {
            val sep = if (path.contains("?")) "&" else "?"
            fixUrl("$path${sep}page=$page")
        }

        val doc = app.get(url, headers = headers()).document
        val items = doc.select("article.movie-card").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/arama?q=${query.replace(" ", "+")}",
            headers = headers()
        ).document
        return doc.select("article.movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers()).document

        val title = doc.selectFirst(".film-title-h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("""\s+Full HD.*$"""), "")
                ?.trim()
            ?: throw ErrorLoadingException("Başlık bulunamadı")

        val poster = doc.selectFirst(".detail-poster img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val backdrop = doc.selectFirst("""link[rel=preload][as=image]""")?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val overview = doc.selectFirst(".detail-synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("""\((\d{4})\)""")
            .find(doc.title())?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let(::fixUrl)
            this.backgroundPosterUrl = backdrop?.let(::fixUrl)
            this.plot = overview
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("FHD_DIAG LOAD_LINKS detail=$data")

        /*
         * Gerçek site akışı:
         *
         * detail GET
         *   -> .vp-face[data-src-type][data-src-id][data-src-token]
         *   -> GET /api/token
         *   -> POST /api/view
         *   -> JSON embed=https://vidmixi.com/embed/...
         *   -> VidMixi ExtractorApi
         *
         * Bu provider artık iframe veya WebView aramaz.
         */
        val detail = try {
            app.get(
                data,
                headers = headers()
            )
        } catch (t: Throwable) {
            println(
                "FHD_DIAG FAIL stage=DETAIL_GET " +
                    "type=${t.javaClass.simpleName} msg=${t.message}"
            )
            return false
        }

        println(
            "FHD_DIAG DETAIL " +
                "status=${detail.code} final=${detail.url} chars=${detail.text.length}"
        )

        val doc = detail.document
        val playButton = doc.selectFirst(
            ".vp-face[data-src-type][data-src-id][data-src-token]"
        )

        if (playButton == null) {
            println(
                "FHD_DIAG FAIL stage=DETAIL " +
                    "reason=PLAYER_BUTTON_NOT_FOUND"
            )
            return false
        }

        val sourceType = playButton.attr("data-src-type").trim()
        val sourceId = playButton.attr("data-src-id").trim()
        val sourceKey = playButton.attr("data-src-token").trim()

        println(
            "FHD_DIAG PLAYER_DATA " +
                "type=$sourceType id=$sourceId " +
                "keyLen=${sourceKey.length} keyPrefix=${sourceKey.take(8)}"
        )

        if (
            sourceType.isBlank() ||
            sourceId.isBlank() ||
            sourceKey.isBlank()
        ) {
            println(
                "FHD_DIAG FAIL stage=DETAIL " +
                    "reason=PLAYER_DATA_EMPTY"
            )
            return false
        }

        val ajaxHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With" to "XMLHttpRequest",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        /*
         * /api/token aynı app client üzerinden çağrılıyor.
         * Böylece site detail isteğinde oluşan session/cookie zinciri korunur.
         */
        val tokenResponse = try {
            app.get(
                "$mainUrl/api/token",
                referer = data,
                headers = ajaxHeaders
            )
        } catch (t: Throwable) {
            println(
                "FHD_DIAG FAIL stage=TOKEN_GET " +
                    "type=${t.javaClass.simpleName} msg=${t.message}"
            )
            return false
        }

        println(
            "FHD_DIAG TOKEN_RESPONSE " +
                "status=${tokenResponse.code} " +
                "contentType=${tokenResponse.headers["content-type"] ?: ""} " +
                "chars=${tokenResponse.text.length}"
        )

        val csrfToken = try {
            JSONObject(tokenResponse.text)
                .optString("token")
                .trim()
        } catch (t: Throwable) {
            println(
                "FHD_DIAG FAIL stage=TOKEN_PARSE " +
                    "type=${t.javaClass.simpleName} msg=${t.message} " +
                    "body=${tokenResponse.text.take(240)}"
            )
            return false
        }

        if (csrfToken.isBlank()) {
            println(
                "FHD_DIAG FAIL stage=TOKEN_PARSE " +
                    "reason=TOKEN_EMPTY body=${tokenResponse.text.take(240)}"
            )
            return false
        }

        println(
            "FHD_DIAG TOKEN_OK " +
                "len=${csrfToken.length} prefix=${csrfToken.take(8)}"
        )

        val viewHeaders = ajaxHeaders + mapOf(
            "Origin" to mainUrl,
            "Content-Type" to "application/x-www-form-urlencoded"
        )

        val viewResponse = try {
            app.post(
                "$mainUrl/api/view",
                referer = data,
                headers = viewHeaders,
                data = mapOf(
                    "t" to sourceType,
                    "i" to sourceId,
                    "k" to sourceKey,
                    "csrf_token" to csrfToken
                )
            )
        } catch (t: Throwable) {
            println(
                "FHD_DIAG FAIL stage=VIEW_POST " +
                    "type=${t.javaClass.simpleName} msg=${t.message}"
            )
            return false
        }

        println(
            "FHD_DIAG VIEW_RESPONSE " +
                "status=${viewResponse.code} " +
                "contentType=${viewResponse.headers["content-type"] ?: ""} " +
                "chars=${viewResponse.text.length}"
        )

        val viewJson = try {
            JSONObject(viewResponse.text)
        } catch (t: Throwable) {
            println(
                "FHD_DIAG FAIL stage=VIEW_PARSE " +
                    "type=${t.javaClass.simpleName} msg=${t.message} " +
                    "body=${viewResponse.text.take(320)}"
            )
            return false
        }

        val ok = viewJson.optBoolean("ok", false)
        val embed = viewJson.optString("embed").trim()

        println(
            "FHD_DIAG VIEW_JSON " +
                "ok=$ok embed=$embed"
        )

        if (!ok || embed.isBlank()) {
            println(
                "FHD_DIAG FAIL stage=VIEW_JSON " +
                    "reason=EMBED_MISSING body=${viewResponse.text.take(320)}"
            )
            return false
        }

        val isVidMixi = runCatching {
            java.net.URI(embed)
                .host
                ?.contains("vidmixi.com", ignoreCase = true) == true
        }.getOrDefault(false)

        if (!isVidMixi) {
            println(
                "FHD_DIAG FAIL stage=VIEW_JSON " +
                    "reason=UNEXPECTED_EMBED_HOST embed=$embed"
            )
            return false
        }

        println("FHD_DIAG VIDMIXI_HANDOFF url=$embed")

        return try {
            val handled = loadExtractor(
                url = embed,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            println("FHD_DIAG EXTRACTOR_DONE handled=$handled")
            handled
        } catch (t: Throwable) {
            println(
                "FHD_DIAG FAIL stage=VIDMIXI_EXTRACTOR " +
                    "type=${t.javaClass.simpleName} msg=${t.message}"
            )
            false
        }
    }
}
