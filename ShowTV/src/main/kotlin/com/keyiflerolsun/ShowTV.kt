package com.keyiflerolsun

// FIXED_FOR_PARS_CLOUDSTREAM_20260828_V5_POSTER_DEDUP

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ShowTV : MainAPI() {

    private val buildFixTag = "PARS-SHOWTV-FIX-20260828-V5-POSTER-DEDUP"

    override var mainUrl = "https://www.showtv.com.tr"
    override var name = "Show TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?pars_section=featured" to "Öne Çıkanlar",
        "$mainUrl/diziler?pars_section=all" to "Tüm Diziler"
    )

    private data class SeriesCard(
        val title: String,
        val url: String,
        val poster: String?
    )

    private data class EpisodeCard(
        val title: String,
        val url: String,
        val poster: String?,
        val season: Int?,
        val episode: Int?
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // pars_section sadece bizim provider içi ayıracımız.
        // Show TV'ye her zaman gerçek /diziler adresini gönderiyoruz.
        val document = app.get(
            "$mainUrl/diziler",
            headers = defaultHeaders()
        ).document

        val allSeries = parseFullSeriesGrid(document)

        if (request.name == "Öne Çıkanlar") {
            // Büyük hero/carousel bölümü.
            // Burada yalnız ilk 6 dizi gösterilir; bütün arşivi buraya basmayız.
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList())
            }

            val featured = allSeries
                .take(6)
                .map { item ->
                    newTvSeriesSearchResponse(
                        item.title,
                        item.url,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = item.poster
                    }
                }

            return newHomePageResponse(
                request.name,
                featured
            )
        }

        /*
         * "Tüm Diziler" ayrı section.
         * İlk ekranda 10 kart gelir.
         * Flutter bu section için "Tümünü Gör" gösterir.
         * Tümünü Gör ekranında page=2,3,4... ile 10'ar 10'ar devam eder.
         */
        val pageSize = 10
        val safePage = page.coerceAtLeast(1)
        val fromIndex = (safePage - 1) * pageSize

        val pageItems = if (fromIndex >= allSeries.size) {
            emptyList()
        } else {
            allSeries
                .drop(fromIndex)
                .take(pageSize)
        }

        val series = pageItems.map { item ->
            newTvSeriesSearchResponse(
                item.title,
                item.url,
                TvType.TvSeries
            ) {
                this.posterUrl = item.poster
            }
        }

        return newHomePageResponse(
            request.name,
            series
        )
    }

    private fun parseFullSeriesGrid(document: Document): List<SeriesCard> {
        val result = LinkedHashMap<String, SeriesCard>()

        document
            .select(
                "div[data-name=box-type6] > a.group[href^=/dizi/tanitim/], " +
                    "div[data-name=box-type6] figure a.group[href^=/dizi/tanitim/]"
            )
            .forEach { anchor ->
                val href = anchor.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach

                val url = absoluteUrl(href)

                val title = anchor.attr("title")
                    .trim()
                    .ifBlank {
                        anchor.closest("div[data-name=box-type6]")
                            ?.selectFirst("figcaption span")
                            ?.text()
                            ?.trim()
                            .orEmpty()
                    }
                    .ifBlank { return@forEach }

                val scope =
                    anchor.closest("div[data-name=box-type6]")
                        ?: anchor

                val poster = (
                    anchor.selectFirst("img")
                        ?: scope.selectFirst("img")
                    )
                    ?.let(::imageUrl)

                val candidate = SeriesCard(
                    title = title,
                    url = url,
                    poster = poster
                )

                val old = result[url]

                result[url] = when {
                    old == null -> candidate
                    old.poster.isNullOrBlank() &&
                        !candidate.poster.isNullOrBlank() -> candidate
                    old.title.isBlank() &&
                        candidate.title.isNotBlank() -> candidate
                    else -> old
                }
            }

        return result.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/diziler",
            headers = defaultHeaders()
        ).document

        val normalized = query.trim().lowercase()

        return parseFullSeriesGrid(document)
            .filter {
                normalized.isBlank() ||
                    it.title.lowercase().contains(normalized)
            }
            .map { item ->
                newTvSeriesSearchResponse(
                    item.title,
                    item.url,
                    TvType.TvSeries
                ) {
                    this.posterUrl = item.poster
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = defaultHeaders(referer = "$mainUrl/diziler")
        ).document

        val title = extractSeriesTitle(document)
            ?: return null

        val poster = extractSeriesPoster(document)
        val plot = extractMeta(document, "meta[name=description]", "content")
            ?: extractMeta(document, "meta[property=og:description]", "content")

        val seriesSlug = extractSeriesSlug(url)
            ?: return null

        /*
         * ÖNEMLİ:
         * Tanıtım sayfası sadece son birkaç bölümü server-side gösteriyor.
         * Buna karşılık gerçek bir bölüm sayfasındaki "BÖLÜMLER / Tüm Bölümler"
         * alanı dizinin tüm sezon/bölüm bağlantılarını içeriyor.
         *
         * Bu yüzden önce SADECE bu diziye ait ilk gerçek bölüm URL'sini buluyoruz,
         * sonra o bölüm sayfasını açıp bütün sezonları oradan topluyoruz.
         */
        val seedEpisodeUrl = document
            .select("a[href^=/dizi/tum_bolumler/], a[href^=https://www.showtv.com.tr/dizi/tum_bolumler/], a[href^=http://www.showtv.com.tr/dizi/tum_bolumler/]")
            .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
            .map(::absoluteUrl)
            .firstOrNull { isEpisodeOfSeries(it, seriesSlug) }

        val episodes = if (seedEpisodeUrl != null) {
            val episodeDocument = app.get(
                seedEpisodeUrl,
                headers = defaultHeaders(referer = url)
            ).document

            parseEpisodes(
                document = episodeDocument,
                seriesSlug = seriesSlug,
                seriesTitle = title
            )
        } else {
            // Çok eski/özel bir dizide bölüm linki bulunamazsa son çare tanıtım sayfası.
            parseEpisodes(
                document = document,
                seriesSlug = seriesSlug,
                seriesTitle = title
            )
        }

        val cloudEpisodes = episodes.map { item ->
            newEpisode(item.url) {
                // CloudStream bölüm numarasını zaten ayrıca gösteriyor.
                // İsme tekrar "129. Bölüm" yazıp çift numara üretmiyoruz.
                this.name = item.title
                this.season = item.season
                this.episode = item.episode
                this.posterUrl = item.poster
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            cloudEpisodes
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
        val document = app.get(
            data,
            headers = defaultHeaders(referer = mainUrl)
        ).document

        var found = false

        // Show TV'nin kendi Hope Player konfigürasyonu.
        // data-hope-video içinde:
        // media.m3u8[].src
        // subtitles[].src
        val configRaw = document
            .selectFirst("[data-hope-video]")
            ?.attr("data-hope-video")
            ?.trim()
            .orEmpty()

        if (configRaw.isNotBlank()) {
            val config = try {
                jacksonObjectMapper().readValue(configRaw, HopeVideoConfig::class.java)
            } catch (_: Exception) {
                null
            }

            config?.media?.m3u8.orEmpty().forEach { stream ->
                val streamUrl = stream.src
                    ?.replace("\\/", "/")
                    ?.trim()
                    ?.takeIf { it.startsWith("http") }
                    ?: return@forEach

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = stream.label?.ifBlank { name } ?: name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                        headers = defaultHeaders(referer = data)
                    }
                )
                found = true
            }

            config?.subtitles.orEmpty().forEach { sub ->
                val subtitleUrl = sub.src
                    ?.replace("\\/", "/")
                    ?.trim()
                    ?.takeIf { it.startsWith("http") }
                    ?: return@forEach

                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = sub.label?.ifBlank { "Türkçe" } ?: "Türkçe",
                        url = subtitleUrl
                    ) {
                        headers = defaultHeaders(referer = data)
                    }
                )
            }
        }

        // HTML yapısı değişirse basit bir yedek çözüm.
        if (!found) {
            val html = document.html()
                .replace("\\/", "/")

            val m3u8Regex =
                Regex("""https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""")

            val urls = m3u8Regex
                .findAll(html)
                .map { it.value }
                .distinct()
                .toList()

            urls.forEachIndexed { index, streamUrl ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (urls.size == 1) name else "$name ${index + 1}",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                        headers = defaultHeaders(referer = data)
                    }
                )
                found = true
            }
        }

        // Son yedek: schema.org VideoObject içindeki contentUrl MP4.
        if (!found) {
            val html = document.html()
                .replace("\\/", "/")

            val mp4 = Regex(
                """"contentUrl"\s*:\s*"(https?://[^"]+\.mp4(?:\?[^"]*)?)""""
            ).find(html)?.groupValues?.getOrNull(1)

            if (!mp4.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = mp4,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                        headers = defaultHeaders(referer = data)
                    }
                )
                found = true
            }
        }

        return found
    }

    private fun parseSeries(document: Document): List<SeriesCard> {
        val result = LinkedHashMap<String, SeriesCard>()

        document
            .select("a[href^=/dizi/tanitim/]")
            .forEach { anchor ->
                val href = anchor.attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach

                val url = absoluteUrl(href)

                val title = anchor.attr("title")
                    .trim()
                    .ifBlank {
                        anchor.closest("li, article, div")
                            ?.selectFirst("figcaption span, h2, h3, h4")
                            ?.text()
                            ?.trim()
                            .orEmpty()
                    }
                    .ifBlank { return@forEach }

                val scope = anchor.closest("li, article, figure, div") ?: anchor

                val poster = (
                    anchor.selectFirst("img")
                        ?: scope.selectFirst("img")
                    )
                    ?.let(::imageUrl)

                val candidate = SeriesCard(
                    title = title,
                    url = url,
                    poster = poster
                )

                val old = result[url]

                // Aynı dizi sayfada birden fazla yerde bulunabiliyor.
                // İlk kart posterli, sonraki menü/slider kaydı postersiz olursa
                // posterli kaydı asla ezme.
                result[url] = when {
                    old == null -> candidate
                    old.poster.isNullOrBlank() && !candidate.poster.isNullOrBlank() ->
                        candidate
                    old.title.isBlank() && candidate.title.isNotBlank() ->
                        candidate
                    else -> old
                }
            }

        return result.values.toList()
    }

    private fun parseEpisodes(
        document: Document,
        seriesSlug: String,
        seriesTitle: String
    ): List<EpisodeCard> {
        val result = LinkedHashMap<String, EpisodeCard>()

        document
            .select("a[href^=/dizi/tum_bolumler/], a[href^=https://www.showtv.com.tr/dizi/tum_bolumler/], a[href^=http://www.showtv.com.tr/dizi/tum_bolumler/]")
            .forEach { anchor ->
                val href = anchor.attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach

                val url = absoluteUrl(href)

                // Başka dizilerin header/promo/öneri linklerini kesin olarak dışarıda bırak.
                if (!isEpisodeOfSeries(url, seriesSlug)) {
                    return@forEach
                }

                val parsed = parseSeasonEpisode(url)
                val seasonNumber = parsed.first
                val episodeNumber = parsed.second

                // Normal bölüm olmayan özel sayfaları, numara yoksa listeye eklemiyoruz.
                // Böylece sezonlar CloudStream'da yanlış "Sezon 1" altında toplanmıyor.
                if (seasonNumber == null || episodeNumber == null) {
                    return@forEach
                }

                val rawTitle = anchor.attr("title")
                    .trim()
                    .ifBlank { anchor.text().trim() }

                // CloudStream kendi episode alanını gösterdiği için
                // "129. Kızılcık Şerbeti 129. Bölüm" gibi tekrar oluşmasın.
                val cleanTitle = cleanEpisodeName(
                    rawTitle = rawTitle,
                    seriesTitle = seriesTitle,
                    episodeNumber = episodeNumber
                )

                val scope = anchor.closest(
                    "li, article, figure, [data-name], .item, .card, .video-item, div"
                ) ?: anchor

                val poster = sequenceOf(
                    anchor.selectFirst("img"),
                    scope.selectFirst("img")
                )
                    .filterNotNull()
                    .mapNotNull(::imageUrl)
                    .firstOrNull()

                val candidate = EpisodeCard(
                    title = cleanTitle,
                    url = url,
                    poster = poster,
                    season = seasonNumber,
                    episode = episodeNumber
                )

                val old = result[url]

                // Aynı bölüm sayfada birden çok kez bulunabilir:
                // üst menüde postersiz, aşağıdaki kartta posterli.
                // Posterli/iyi kaydı tercih et.
                result[url] = when {
                    old == null -> candidate
                    old.poster.isNullOrBlank() && !candidate.poster.isNullOrBlank() ->
                        candidate
                    old.title.isBlank() && candidate.title.isNotBlank() ->
                        candidate
                    else -> old
                }
            }

        return result.values
            .sortedWith(
                compareBy<EpisodeCard>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )
    }

    private fun extractSeriesSlug(url: String): String? {
        return Regex(
            """/dizi/tanitim/([^/?#]+)/?""",
            RegexOption.IGNORE_CASE
        ).find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isEpisodeOfSeries(
        episodeUrl: String,
        seriesSlug: String
    ): Boolean {
        val uri = runCatching {
            java.net.URI(episodeUrl)
        }.getOrNull() ?: return false

        val host = uri.host
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return false

        if (host != "showtv.com.tr") {
            return false
        }

        val path = uri.path.orEmpty()

        return Regex(
            """^/dizi/tum_bolumler/${Regex.escape(seriesSlug)}(?:-|/)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(path)
    }

    private fun cleanEpisodeName(
        rawTitle: String,
        seriesTitle: String,
        episodeNumber: Int
    ): String {
        var value = rawTitle
            .replace(Regex("""^\s*Alt\s*Yazılı\s*""", RegexOption.IGNORE_CASE), "")
            .trim()

        value = value
            .replace(
                Regex(
                    """^\s*${Regex.escape(seriesTitle)}\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()

        value = value
            .replace(
                Regex(
                    """^\s*${episodeNumber}\.?\s*Bölüm\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()

        // Boş kalırsa CloudStream için sade başlık kullan.
        return value.ifBlank { "Bölüm" }
    }

    private fun parseSeasonEpisode(url: String): Pair<Int?, Int?> {
        val match = Regex(
            """sezon-(\d+)-bolum-(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(url)

        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

        return season to episode
    }

    private fun extractSeriesTitle(document: Document): String? {
        // Önce TVSeries JSON-LD içindeki name değerini dene.
        document
            .select("script[type=application/ld+json]")
            .forEach { script ->
                val text = script.data().ifBlank { script.html() }

                if (
                    text.contains("\"@type\":\"TVSeries\"") ||
                    text.contains("\"@type\": \"TVSeries\"")
                ) {
                    Regex(
                        """"name"\s*:\s*"([^"]+)""""
                    ).find(text)?.groupValues?.getOrNull(1)?.let {
                        return decodeJsonText(it)
                    }
                }
            }

        return extractMeta(document, "meta[property=og:title]", "content")
            ?.substringBefore(" - Show TV")
            ?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - Show TV").trim()
    }

    private fun extractSeriesPoster(document: Document): String? {
        return extractMeta(document, "meta[property=og:image]", "content")
            ?: document
                .selectFirst("script[type=application/ld+json]")
                ?.html()
                ?.replace("\\/", "/")
                ?.let { text ->
                    Regex(
                        """"(?:thumbnailUrl|image)"\s*:\s*"(https?://[^"]+)""""
                    ).find(text)?.groupValues?.getOrNull(1)
                }
    }

    private fun extractMeta(
        document: Document,
        selector: String,
        attr: String
    ): String? {
        return document
            .selectFirst(selector)
            ?.attr(attr)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun imageUrl(img: Element): String? {
        val raw = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src")
        ).firstOrNull {
            it.isNotBlank() &&
                !it.contains("transparent.gif", ignoreCase = true)
        } ?: return null

        return absoluteUrl(raw)
    }

    private fun absoluteUrl(url: String): String {
        val clean = url
            .replace("&amp;", "&")
            .trim()

        return when {
            clean.startsWith("https://") || clean.startsWith("http://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$mainUrl/$clean"
        }
    }

    private fun decodeJsonText(value: String): String {
        return value
            .replace("\\u015f", "ş")
            .replace("\\u015e", "Ş")
            .replace("\\u011f", "ğ")
            .replace("\\u011e", "Ğ")
            .replace("\\u0131", "ı")
            .replace("\\u0130", "İ")
            .replace("\\u00fc", "ü")
            .replace("\\u00dc", "Ü")
            .replace("\\u00f6", "ö")
            .replace("\\u00d6", "Ö")
            .replace("\\u00e7", "ç")
            .replace("\\u00c7", "Ç")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }

    private fun defaultHeaders(
        referer: String = mainUrl
    ): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to referer
        )
    }
}
