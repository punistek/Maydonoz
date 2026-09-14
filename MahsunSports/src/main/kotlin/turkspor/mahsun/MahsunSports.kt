package turkspor.mahsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException

class MahsunSports(private val domains: DomainResolver, private val artwork: ChannelArtwork) : MainAPI() {
    override var mainUrl = DomainResolver.GATEWAY
    override var name = "MahsunSports • TurkSpor"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val mainPage = mainPageOf("all" to "Canlı Spor")

    private fun SportsChannel.stableUrl(): String = "${DomainResolver.GATEWAY}turkspor?id=${URLEncoder.encode(id, "UTF-8")}&title=${URLEncoder.encode(title, "UTF-8")}" 
    private fun SportsChannel.result(): SearchResponse = newLiveSearchResponse(
        ChannelBranding.forChannel(this).title, stableUrl(), TvType.Live, false
    ) { posterUrl = artwork.poster(this@result) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        turkspor.common.ChannelRules.refresh()
        val site = domains.resolve()
        artwork.prepare(site.channels)
        return newHomePageResponse(turkspor.common.ChannelGroups.sections(site.channels) { it.title }.map { (category, items) ->
            HomePageList(category, items.map { it.result() }, isHorizontalImages = true)
        }, false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        turkspor.common.ChannelRules.refresh()
        val term = query.lowercase(Locale.forLanguageTag("tr"))
        val items = domains.resolve().channels.filter { turkspor.common.ChannelRules.visible(it.title) }.filter {
            (it.title + " " + ChannelBranding.forChannel(it).title).lowercase(Locale.forLanguageTag("tr")).contains(term)
        }.distinctBy { it.id }
        artwork.prepare(items)
        return items.map { it.result() }
    }
    private suspend fun currentChannel(url: String): SportsChannel {
        val id = SportsParser.queryParam(url, "id") ?: throw ErrorLoadingException("Kanal kimliği eksik")
        val title = SportsParser.queryParam(url, "title")
        val site = domains.resolve()
        return site.channels.filter { turkspor.common.ChannelRules.visible(it.title) }.firstOrNull { it.id == id && it.title == title }
            ?: site.channels.filter { turkspor.common.ChannelRules.visible(it.title) }.firstOrNull { it.id == id }
            ?: throw ErrorLoadingException("Bu yayın güncel listede yok; ana sayfayı yenileyin.")
    }
    override suspend fun load(url: String): LoadResponse {
        val channel = currentChannel(url)
        val brand = ChannelBranding.forChannel(channel)
        artwork.prepare(listOf(channel))
        return newLiveStreamLoadResponse(brand.title, url, channel.stableUrl()) {
            posterUrl = artwork.poster(channel)
            plot = turkspor.common.ChannelGroups.NOTICE
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val channel = currentChannel(data)

        // Mahsun's channels[] already gives a different event.html?id=... URL for each
        // channel. The current site resolves the HLS at browser runtime. Trying to parse
        // one literal filename (for example batutest.m3u8) from static HTML caused every
        // channel to collapse onto the same stream. Preserve the selected channel URL and
        // let Baba Burda's existing WebView resolver capture that channel's real request.
        val playerUrl = channel.player
        val siteRoot = domains.currentUrl
        val siteOrigin = runCatching {
            URI(siteRoot).let { "${it.scheme}://${it.authority}" }
        }.getOrDefault(siteRoot.trimEnd('/'))

        System.out.println("[MAHSUN_V3] WEBVIEW_HANDOFF channel=${channel.title} id=${channel.id} url=$playerUrl")

        callback(
            newExtractorLink(
                source = name,
                name = "${ChannelBranding.forChannel(channel).title} • Browser",
                url = playerUrl,
                type = ExtractorLinkType.VIDEO,
            ) {
                referer = "$siteOrigin/"
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to DomainResolver.UA,
                    "Referer" to "$siteOrigin/",
                    "Origin" to siteOrigin,
                    "X-PARS-WEBVIEW" to "1",
                    "X-PARS-DETAIL-REFERER" to "$siteOrigin/",
                )
            }
        )
        return true
    }
}