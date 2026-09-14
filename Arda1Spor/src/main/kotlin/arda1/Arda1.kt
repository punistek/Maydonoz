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
import java.net.URI
import java.util.Locale

private data class ArdaChannel(
    val slug: String,
    val title: String,
    val posterPath: String,
)

private data class ProvenStream(
    val url: String,
)

class Arda1(private val domains: DomainResolver) : MainAPI() {
    override var mainUrl = DomainResolver.BOOTSTRAP
    override var name = "Arda1"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val mainPage = mainPageOf("channels" to "Kanallar")

    // Arda ana sayfasından doğrulanmış kanal kataloğu. Site BTK/TLS engelindeyken
    // arayüzün tamamen boş kalmaması için yalnız metadata fallback olarak tutulur.
    // Buradaki sluglardan HLS URL TÜRETİLMEZ.
    private val fallbackChannels = listOf(
        ArdaChannel("bein-sports-1", "BEIN SPORTS 1", "assets/img/kanal/beinsports1.png"),
        ArdaChannel("bein-sports-2", "BEIN SPORTS 2", "assets/img/kanal/beinsports2.png"),
        ArdaChannel("bein-sports-3", "BEIN SPORTS 3", "assets/img/kanal/beinsports3.png"),
        ArdaChannel("bein-sports-4", "BEIN SPORTS 4", "assets/img/kanal/beinsports4.png"),
        ArdaChannel("bein-sports-5", "BEIN SPORTS 5", "assets/img/kanal/beinsports5.png"),
        ArdaChannel("bein-sports-max-1", "BEIN SPORTS MAX 1", "assets/img/kanal/beinsportsmax1.png"),
        ArdaChannel("bein-sports-max-2", "BEIN SPORTS MAX 2", "assets/img/kanal/beinsportsmax2.png"),
        ArdaChannel("s-sport", "S SPORT", "assets/img/kanal/ssport1.png"),
        ArdaChannel("s-sport-2", "S SPORT 2", "assets/img/kanal/ssport2.png"),
        ArdaChannel("trt-spor", "TRT SPOR", "assets/img/kanal/trtspornew.png"),
        ArdaChannel("trt-1", "TRT 1", "assets/img/kanal/trt1.png"),
        ArdaChannel("a-spor", "A SPOR", "assets/img/kanal/aspornew.png"),
    )

    // YALNIZCA network/curl ile kanıtlanmış yayın URL'leri.
    // Kesinlikle slug -> m3u8 isim üretimi yoktur.
    private val provenStreams = mapOf(
        "bein-sports-1" to ProvenStream(
            "https://ladyboy.taylandpattaya.cfd//hls/bein1.m3u8"
        ),
        "bein-sports-2" to ProvenStream(
            "https://ladyboy.taylandpattaya.cfd//bein2/tracks-v1a1/mono.m3u8"
        ),
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
        domains.resolveOrFallback()?.let { mainUrl = it }
        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Kanallar",
                    fallbackChannels.map { it.searchResponse() },
                    isHorizontalImages = true,
                )
            ),
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
        val slug = runCatching {
            URI(url).path.substringAfter("/mac-izle/").trim('/')
        }.getOrDefault("")

        val channel = fallbackChannels.firstOrNull { it.slug == slug }
            ?: throw ErrorLoadingException("Arda1 kanalı bulunamadı: $slug")

        domains.resolveOrFallback()?.let { mainUrl = it }
        val currentDetail = detailUrl(channel)

        return newLiveStreamLoadResponse(channel.title, currentDetail, currentDetail) {
            posterUrl = posterUrl(channel)
            plot = "ArdaSpor canlı kanal"
        }
    }

    private suspend fun verifyProvenHls(
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
            val response = app.get(
                url,
                referer = rootReferer,
                headers = headers,
                timeout = 10,
            )
            val ok = response.code in 200..299 &&
                response.text.trimStart().startsWith("#EXTM3U")

            System.out.println(
                "[ARDA1_V3] PROVEN_HLS_PROBE code=${response.code} ok=$ok url=$url final=${response.url}"
            )

            if (ok) response.url else null
        }.getOrElse { error ->
            System.out.println(
                "[ARDA1_V3] PROVEN_HLS_FAIL url=$url ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        domains.resolveOrFallback()?.let { mainUrl = it }

        val slug = runCatching {
            URI(data).path.substringAfter("/mac-izle/").trim('/')
        }.getOrDefault("")

        val channel = fallbackChannels.firstOrNull { it.slug == slug }
            ?: throw ErrorLoadingException("Arda1 kanal eşlemesi yok: $slug")

        val origin = originOf(mainUrl)
        val rootReferer = "$origin/"
        val commonHeaders = mapOf(
            "User-Agent" to DomainResolver.UA,
            "Origin" to origin,
            "Accept" to "*/*",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
        )

        System.out.println(
            "[ARDA1_V3] LOAD title=${channel.title} slug=$slug domain=$mainUrl"
        )

        // 1) Sadece kanıtlanmış direct HLS varsa kullan.
        // V2'deki /hls/${'$'}token.m3u8 ve corestream tahminleri TAMAMEN kaldırıldı.
        val proven = provenStreams[slug]
        if (proven != null) {
            val finalUrl = verifyProvenHls(proven.url, rootReferer, origin)
            if (finalUrl != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = channel.title,
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        referer = rootReferer
                        quality = Qualities.Unknown.value
                        this.headers = commonHeaders
                    }
                )
                System.out.println(
                    "[ARDA1_V3] PROVEN_HLS_OK title=${channel.title} url=$finalUrl"
                )
                return true
            }
        }

        // 2) Kanıtlanmış direct HLS yoksa URL uydurma.
        // Gerçek site zincirini Baba Burda resolver'a ver:
        // /mac-izle/<slug> -> site iframe/player -> runtime network -> gerçek medya.
        val currentDetail = detailUrl(channel)
        callback(
            newExtractorLink(
                source = name,
                name = "${channel.title} • Runtime",
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
                    "X-PARS-ARDA-SLUG" to slug,
                )
            }
        )

        System.out.println(
            "[ARDA1_V3] RUNTIME_HANDOFF title=${channel.title} slug=$slug url=$currentDetail"
        )
        return true
    }
}
