package arda1

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

private data class ArdaChannel(
    val slug: String,
    val title: String,
    val detailUrl: String,
    val poster: String?,
)

class Arda1(private val domains: DomainResolver) : MainAPI() {
    override var mainUrl = DomainResolver.BOOTSTRAP
    override var name = "Arda1"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val mainPage = mainPageOf("channels" to "Kanallar")

    private suspend fun channels(): Pair<String, List<ArdaChannel>> {
        val site = domains.resolve()
        mainUrl = site.url
        val doc = Jsoup.parse(site.html, site.url)

        // Kanıtlanan 2026-09-14 yapı:
        // gerçek TV kanalları home HTML içinde class="eventClick Diger" olarak bulunuyor.
        val items = doc.select("a.eventClick.Diger[href*='/mac-izle/']")
            .mapNotNull { a ->
                val href = a.absUrl("href").trim()
                val uri = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
                val slug = uri.path.orEmpty()
                    .substringAfter("/mac-izle/", "")
                    .trim('/')
                if (!Regex("[a-z0-9-]{1,100}").matches(slug)) return@mapNotNull null

                val title = sequenceOf(
                    a.selectFirst("img[alt]")?.attr("alt"),
                    a.text(),
                ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                    .firstOrNull()
                    ?: return@mapNotNull null

                val poster = a.selectFirst("img[src]")
                    ?.absUrl("src")
                    ?.takeIf(String::isNotBlank)

                ArdaChannel(slug, title, href, poster)
            }
            .distinctBy { it.slug }

        if (items.isEmpty()) {
            throw ErrorLoadingException("Arda1 kanal listesi bulunamadı: a.eventClick.Diger")
        }

        return site.url to items
    }

    private fun ArdaChannel.searchResponse(): SearchResponse =
        newLiveSearchResponse(title, detailUrl, TvType.Live, false) {
            posterUrl = poster
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (_, list) = channels()
        return newHomePageResponse(
            listOf(HomePageList("Kanallar", list.map { it.searchResponse() }, isHorizontalImages = true)),
            false,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase(Locale.forLanguageTag("tr"))
        val (_, list) = channels()
        return list.filter { it.title.lowercase(Locale.forLanguageTag("tr")).contains(term) }
            .map { it.searchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val (_, list) = channels()
        val channel = list.firstOrNull { it.detailUrl == url }
            ?: throw ErrorLoadingException("Arda1 kanalı güncel listede bulunamadı")

        return newLiveStreamLoadResponse(channel.title, channel.detailUrl, channel.detailUrl) {
            posterUrl = channel.poster
            plot = "ArdaSpor canlı kanal"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val site = domains.resolve()
        val siteOrigin = URI(site.url).let { "${it.scheme}://${it.authority}" }
        val rootReferer = "$siteOrigin/"
        val slug = runCatching { URI(data).path.substringAfter("/mac-izle/").trim('/') }.getOrDefault("")

        System.out.println("[ARDA1] LOAD slug=$slug detail=$data domain=${site.url}")

        // Şu an kullanıcı tarafından Network/curl ile KANITLANAN tek doğrudan yayın.
        // Diğer kanal adlarını tahmin ederek bein2/bein3 üretmiyoruz.
        if (slug == "bein-sports-1") {
            val stream = "https://ladyboy.taylandpattaya.cfd//hls/bein1.m3u8"
            val headers = mapOf(
                "User-Agent" to DomainResolver.UA,
                "Origin" to siteOrigin,
                "Accept" to "*/*",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
            )

            // Önce manifest gerçekten HLS mi doğrula. Çalışmayan linki player'a göndermiyoruz.
            val probe = app.get(stream, referer = rootReferer, headers = headers, timeout = 12)
            val ok = probe.code == 200 && probe.text.trimStart().startsWith("#EXTM3U")
            System.out.println("[ARDA1] PROVEN_HLS code=${probe.code} ok=$ok final=${probe.url}")
            if (!ok) throw ErrorLoadingException("Arda1 BeIN Sports 1 HLS doğrulanamadı (${probe.code})")

            callback(
                newExtractorLink(
                    source = name,
                    name = "BEIN SPORTS 1",
                    url = probe.url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = rootReferer
                    quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        }

        // Detail sayfasındaki gerçek player iframe'ini logla; sonraki kanal çözümleri bunun
        // runtime ağına göre eklenecek. Burada dosya adı/domain tahmini yapılmaz.
        val detail = app.get(
            data,
            referer = rootReferer,
            headers = mapOf("User-Agent" to DomainResolver.UA),
            timeout = 12,
        )
        val frame = if (detail.code == 200) {
            Jsoup.parse(detail.text, detail.url)
                .selectFirst("iframe[src*='/channel/watch/']")
                ?.absUrl("src")
                ?.takeIf(String::isNotBlank)
        } else null

        System.out.println("[ARDA1] UNMAPPED slug=$slug detailCode=${detail.code} frame=${frame.orEmpty()}")
        throw ErrorLoadingException(
            "Arda1: $slug için gerçek HLS henüz kanıtlanmadı. ARDA1 UNMAPPED logu ile çözülecek."
        )
    }
}
