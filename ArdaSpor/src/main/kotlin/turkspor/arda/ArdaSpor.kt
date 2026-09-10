package turkspor.arda

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException

class ArdaSpor(private val domains: DomainResolver, private val artwork: ChannelArtwork) : MainAPI() {
    override var mainUrl = DomainResolver.GATEWAY
    override var name = "ArdaSpor • TurkSpor"
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
        val page = app.get(channel.player, referer = domains.currentUrl, headers = mapOf("User-Agent" to DomainResolver.UA), timeout = 15)
        if (page.code != 200) throw ErrorLoadingException("Oynatıcı yanıt vermedi (${page.code}).")
        val playerOrigin = URI(page.url).let { "${it.scheme}://${it.authority}/" }
        val headers = mapOf("User-Agent" to DomainResolver.UA, "Origin" to playerOrigin.trimEnd('/'))
        suspend fun emit(stream: String?): Boolean {
            if (stream == null) return false
            val playlist = app.get(stream, referer = playerOrigin, headers = headers, timeout = 12)
            if (playlist.code != 200 || !playlist.text.trimStart().startsWith("#EXTM3U")) return false
            turkspor.common.HlsQuality.links(name,ChannelBranding.forChannel(channel).title,playlist.url,playlist.text,playerOrigin,headers).forEach(callback)
            return true
        }
        SportsParser.streamEndpoint(page.text,channel.id)?.let { endpoint ->
            try {
                val response = app.get(endpoint,referer=playerOrigin,headers=headers,timeout=10)
                if (response.code == 200 && emit(SportsParser.streamResponse(response.text))) return true
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        SportsParser.cinemaRequest(page.text,channel.id)?.let { request ->
            try {
                val response = app.post(request.url,referer=playerOrigin,headers=headers,json=request.body,timeout=10)
                if (response.code == 200 && emit(SportsParser.streamResponse(response.text))) return true
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        throw ErrorLoadingException("Bu kanalın yayın servisi yanıt vermiyor; maç saatinde veya WARP ile tekrar deneyin.")
    }
}
