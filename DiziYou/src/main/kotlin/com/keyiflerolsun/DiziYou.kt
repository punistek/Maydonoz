// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class DiziYou : MainAPI() {
    override var mainUrl              = "https://www.diziyou.one"
    override var name                 = "DiziYou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)
    
    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye
    
    private val genrePages = linkedMapOf(
        "Aile" to "Aile",
        "Aksiyon" to "Aksiyon",
        "Animasyon" to "Animasyon",
        "Belgesel" to "Belgesel",
        "Bilim Kurgu" to "Bilim%20Kurgu",
        "Dram" to "Dram",
        "Fantazi" to "Fantazi",
        "Gerilim" to "Gerilim",
        "Gizem" to "Gizem",
        "Komedi" to "Komedi",
        "Korku" to "Korku",
        "Macera" to "Macera",
        "Politik" to "Politik",
        "Savaş" to "Sava%C5%9F",
        "Suç" to "Su%C3%A7",
        "Vahşi Batı" to "Vah%C5%9Fi%20Bat%C4%B1",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Öne Çıkanlar",
        *genrePages.map { (name, encoded) ->
            "$mainUrl/dizi-arsivi/?tur=$encoded" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isFeatured = request.name == "Öne Çıkanlar"

        if (isFeatured) {
            // Ana sayfadaki "Dikkat Çeken Diziler" bölümü, uygulamadaki
            // büyük Öne Çıkanlar carousel'i için kullanılır.
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList())
            }

            val document = app.get(mainUrl).document

            val featured = document
                .select("div.incontentyeni div#list-series-main")
                .mapNotNull { it.toHomeCardResult() }
                .distinctBy { it.url }

            return newHomePageResponse(request.name, featured)
        }

        // Dizi Arşivi filtre sayfasının gerçek sayfalama biçimi:
        // /dizi-arsivi/page/2/?tur=Aile
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            val query = request.data.substringAfter("?", "")
            val suffix = if (query.isBlank()) "" else "?$query"
            "$mainUrl/dizi-arsivi/page/$page/$suffix"
        }

        val document = app.get(pageUrl).document

        // Filtrelenmiş arşivde her gerçek dizi kartı div.single-item.
        val items = document
            .select("div.seriescontent div.single-item")
            .mapNotNull { it.toArchiveResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toHomeCardResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val title = (
            anchor.attr("aria-label").takeIf { it.isNotBlank() }
                ?: anchor.attr("title").takeIf { it.isNotBlank() }
                ?: selectFirst("div.cat-title-main")?.text()
        )?.trim() ?: return null

        val poster = fixUrlNull(
            selectFirst("div.cat-img-main img")?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?: selectFirst("div.cat-img-main img")?.attr("src")
        )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    private fun Element.toArchiveResult(): SearchResponse? {
        val titleAnchor =
            selectFirst("div#categorytitle a[href]")
                ?: selectFirst("div.cat-img a[href]")
                ?: return null

        val href = fixUrlNull(titleAnchor.attr("href")) ?: return null
        val title = titleAnchor.text().trim().ifBlank {
            titleAnchor.attr("title").trim()
        }
        if (title.isBlank()) return null

        val image = selectFirst("div.cat-img img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div#categorytitle a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div#categorytitle a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.incontent div#list-series").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src"))
        val description     = document.selectFirst("div.diziyou_desc")?.ownText()?.trim()
        val year            = document.selectFirst("span.dizimeta:contains(Yapım Yılı)")?.nextSibling()?.toString()?.trim()?.toIntOrNull()
        val tags            = document.select("div.genres a").map { it.text() }
        val actors          = document.selectFirst("span.dizimeta:contains(Oyuncular)")?.nextSibling()?.toString()?.trim()?.split(", ")?.map { Actor(it) }
        val trailer         = document.selectFirst("iframe.trailer-video")?.attr("src")

        val episodes = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.ownText()?.trim() ?: return@mapNotNull null
            val epHref    = it.closest("a")?.attr("href")?.let { href -> fixUrlNull(href) } ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\. Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\. Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name = it.selectFirst("div.bolumismi")?.text()?.trim()?.replace(Regex("""[()]"""), "")?.trim() ?: epName
                this.season = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZY", "data » $data")
        val document = app.get(data).document

        val itemId     = document.selectFirst("iframe#diziyouPlayer")?.attr("src")?.split("/")?.lastOrNull()?.substringBefore(".html") ?: return false
        Log.d("DZY", "itemId » $itemId")

        val subTitles  = mutableListOf<DiziyouSubtitle>()
        val streamUrls = mutableListOf<DiziyouStream>()
        val storage    = mainUrl.replace("www", "storage")

        document.select("span.diziyouOption").forEach {
            val optId   = it.attr("id")

            if (optId == "turkceAltyazili") {
                subTitles.add(DiziyouSubtitle("Turkish", "${storage}/subtitles/${itemId}/tr.vtt"))
                streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
            }

            if (optId == "ingilizceAltyazili") {
                subTitles.add(DiziyouSubtitle("English", "${storage}/subtitles/${itemId}/en.vtt"))
                streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
            }

            if (optId == "turkceDublaj") {
                streamUrls.add(DiziyouStream("Türkçe Dublaj", "${storage}/episodes/${itemId}_tr/play.m3u8"))
            }
        }

        for (sub in subTitles) {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = sub.name,
                    url  = fixUrl(sub.url)
                )
            )
        }

        for (stream in streamUrls) {
            callback.invoke(
             newExtractorLink(
                source = this.name,
                name = this.name,
                url = stream.url,
                type    = INFER_TYPE
            ) {
                headers = mapOf("Referer" to "${mainUrl}/")
                quality = Qualities.Unknown.value
            }
            )
        }

        return true
    }

    data class DiziyouSubtitle(val name: String, val url: String)
    data class DiziyouStream(val name: String, val url: String)
}
