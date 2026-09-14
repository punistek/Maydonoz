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
    val posterPath: String,
    val streamToken: String,
    val legacyPath: String,
)

class Arda1(private val domains: DomainResolver) : MainAPI() {
    override var mainUrl = DomainResolver.BOOTSTRAP
    override var name = "Arda1"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val mainPage = mainPageOf("channels" to "Kanallar")

    // 2026-09-14 Arda ana sayfasında doğrulanan TV kanal listesi.
    // Site BTK/TLS engeline düşse bile liste ve poster kaybolmasın diye fallback olarak tutulur.
    private val fallbackChannels = listOf(
        ArdaChannel("bein-sports-1", "BEIN SPORTS 1", "assets/img/kanal/beinsports1.png", "bein1", "bein1"),
        ArdaChannel("bein-sports-2", "BEIN SPORTS 2", "assets/img/kanal/beinsports2.png", "bein2", "bein2"),
        ArdaChannel("bein-sports-3", "BEIN SPORTS 3", "assets/img/kanal/beinsports3.png", "bein3", "bein3"),
        ArdaChannel("bein-sports-4", "BEIN SPORTS 4", "assets/img/kanal/beinsports4.png", "bein4", "bein4"),
        ArdaChannel("bein-sports-5", "BEIN SPORTS 5", "assets/img/kanal/beinsports5.png", "bein5", "bein5"),
        ArdaChannel("bein-sports-max-1", "BEIN SPORTS MAX 1", "assets/img/kanal/beinsportsmax1.png", "beinmax1", "beinmax1"),
        ArdaChannel("bein-sports-max-2", "BEIN SPORTS MAX 2", "assets/img/kanal/beinsportsmax2.png", "beinmax2", "beinmax2"),
        ArdaChannel("s-sport", "S SPORT", "assets/img/kanal/ssport1.png", "s-sport", "s-sport"),
        ArdaChannel("s-sport-2", "S SPORT 2", "assets/img/kanal/ssport2.png", "s-sport2", "s-sport2"),
        ArdaChannel("trt-spor", "TRT SPOR", "assets/img/kanal/trtspornew.png", "trt-spor", "trt-spor"),
        ArdaChannel("trt-1", "TRT 1", "assets/img/kanal/trt1.png", "trt1", "trt1"),
        ArdaChannel("a-spor", "A SPOR", "assets/img/kanal/aspornew.png", "aspor", "aspor"),
    )

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.authority}"
    }.getOrDefault("https://www.ardaspor30.top")

    private fun detailUrl(channel: ArdaChannel): String =
        "${mainUrl.trimEnd('/')}/mac-izle/${channel.slug}"

    private fun posterUrl(channel: ArdaChannel): String =
        "${mainUrl.trimEnd('/')}/${channel.posterPath}"

    private fun ArdaChannel.searchResponse(): SearchResponse =
        newLiveSearchResponse(title, detailUrl(this), TvType.Live, false) {
            posterUrl = posterUrl(this@searchResponse)
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Domain erişilebilirse güncel domaini öğren; engelliyse fallback URL ile devam et.
        domains.resolveOrFallback()?.let { mainUrl = it }
        return newHomePageResponse(
            listOf(HomePageList("Kanallar", fallbackChannels.map { it.searchResponse() }, isHorizontalImages = true)),
            false,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase(Locale.forLanguageTag("tr"))
        domains.resolveOrFallback()?.let { mainUrl = it }
        return fallbackChannels
            .filter { it.title.lowercase(Locale.forLanguageTag("tr")).contains(term) }
            .map { it.searchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = runCatching { URI(url).path.substringAfter("/mac-izle/").trim('/') }.getOrDefault("")
        val channel = fallbackChannels.firstOrNull { it.slug == slug }
            ?: throw ErrorLoadingException("Arda1 kanalı bulunamadı: $slug")
        domains.resolveOrFallback()?.let { mainUrl = it }
        val currentDetail = detailUrl(channel)
        return newLiveStreamLoadResponse(channel.title, currentDetail, currentDetail) {
            posterUrl = posterUrl(channel)
            plot = "ArdaSpor canlı kanal"
        }
    }

    private suspend fun verifiedHls(
        url: String,
        rootReferer: String,
        origin: String,
    ): String? {
        val headers = mapOf(
            "User-Agent" to DomainResolver.UA,
            "Origin" to origin,
            "Accept" to "*/*",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
        )
        return runCatching {
            val response = app.get(url, referer = rootReferer, headers = headers, timeout = 10)
            val ok = response.code in 200..299 && response.text.trimStart().startsWith("#EXTM3U")
            System.out.println("[ARDA1_V2] HLS_PROBE code=${response.code} ok=$ok candidate=$url final=${response.url}")
            if (ok) response.url else null
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        domains.resolveOrFallback()?.let { mainUrl = it }

        val slug = runCatching { URI(data).path.substringAfter("/mac-izle/").trim('/') }.getOrDefault("")
        val channel = fallbackChannels.firstOrNull { it.slug == slug }
            ?: throw ErrorLoadingException("Arda1 kanal eşlemesi yok: $slug")

        val origin = originOf(mainUrl)
        val rootReferer = "$origin/"
        val headers = mapOf(
            "User-Agent" to DomainResolver.UA,
            "Origin" to origin,
            "Accept" to "*/*",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
        )

        System.out.println("[ARDA1_V2] LOAD title=${channel.title} slug=$slug token=${channel.streamToken} domain=$mainUrl")

        // 1) Güncel 2026-09-14 BeIN1 Network zincirinde kanıtlanan CDN ailesi.
        // Kanal tokenı eski Arda kaynak/test yapısından gelir; aday URL ancak #EXTM3U doğrulanırsa kabul edilir.
        val candidates = linkedSetOf<String>()
        candidates += "https://ladyboy.taylandpattaya.cfd//hls/${channel.streamToken}.m3u8"

        // 2) Eski çalışan Arda parser/test yapısında görülen ikinci HLS biçimi.
        // Stale ise doğrulama reddeder; player'a asla kör URL gönderilmez.
        candidates += "https://corestream.siteyaptim.live//${channel.legacyPath}/tracks-v1a1/mono.m3u8"
        candidates += "https://corestream.siteyaptim.live//hls/${channel.streamToken}.m3u8"

        for (candidate in candidates) {
            val finalUrl = verifiedHls(candidate, rootReferer, origin) ?: continue
            callback(
                newExtractorLink(
                    source = name,
                    name = channel.title,
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = rootReferer
                    quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            System.out.println("[ARDA1_V2] HLS_OK title=${channel.title} url=$finalUrl")
            return true
        }

        // 3) Direct CDN bulunamazsa detay sayfasını native Baba Burda resolver'a bırak.
        // Bu yol gerçek runtime isteğini yakalayabilir; kanal URL'si hardcode HLS'e zorlanmaz.
        val currentDetail = "${mainUrl.trimEnd('/')}/mac-izle/${channel.slug}"
        callback(
            newExtractorLink(
                source = name,
                name = "${channel.title} • Browser",
                url = currentDetail,
                type = ExtractorLinkType.VIDEO,
            ) {
                referer = rootReferer
                quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to DomainResolver.UA,
                    "Referer" to rootReferer,
                    "Origin" to origin,
                    "X-PARS-WEBVIEW" to "1",
                    "X-PARS-DETAIL-REFERER" to rootReferer,
                )
            }
        )
        System.out.println("[ARDA1_V2] WEBVIEW_FALLBACK title=${channel.title} url=$currentDetail")
        return true
    }
}
