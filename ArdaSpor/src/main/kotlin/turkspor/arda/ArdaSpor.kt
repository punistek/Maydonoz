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
        val siteRoot = domains.currentUrl
        val siteOrigin = runCatching {
            URI(siteRoot).let { "${it.scheme}://${it.authority}" }
        }.getOrDefault(siteRoot.trimEnd('/'))
        val hlsReferer = "$siteOrigin/"
        val headers = mapOf(
            "User-Agent" to DomainResolver.UA,
            "Origin" to siteOrigin,
            "Accept" to "*/*",
        )

        System.out.println("[ARDASPOR] LOAD channel=${channel.title} id=${channel.id} page=${channel.player}")

        suspend fun emit(stream: String?): Boolean {
            if (stream.isNullOrBlank()) return false
            System.out.println("[ARDASPOR] HLS_TRY $stream")
            return try {
                val playlist = app.get(stream, referer = hlsReferer, headers = headers, timeout = 12)
                val isHls = playlist.code == 200 && playlist.text.trimStart().startsWith("#EXTM3U")
                System.out.println("[ARDASPOR] HLS_CHECK code=${playlist.code} ok=$isHls final=${playlist.url}")
                if (!isHls) return false
                turkspor.common.HlsQuality.links(
                    name,
                    ChannelBranding.forChannel(channel).title,
                    playlist.url,
                    playlist.text,
                    hlsReferer,
                    headers,
                ).forEach(callback)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.out.println("[ARDASPOR] HLS_FAIL ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        // If the channel list itself ever carries a direct HLS, use it first.
        if (emit(channel.directStream)) return true

        // Current site: /mac-izle/... contains an iframe /channel/watch/... .
        val detail = app.get(
            channel.player,
            referer = hlsReferer,
            headers = mapOf("User-Agent" to DomainResolver.UA),
            timeout = 15,
        )
        if (detail.code != 200) throw ErrorLoadingException("Oynatıcı yanıt vermedi (${detail.code}).")

        // Rare case: HLS is already present in the detail page.
        for (stream in SportsParser.directHlsUrls(detail.text, detail.url)) {
            if (emit(stream)) return true
        }

        // Actual 2026-09-14 structure: recurse into /channel/watch/<slug> iframe.
        val frames = SportsParser.playerFrames(detail.text, detail.url)
        System.out.println("[ARDASPOR] PLAYER_FRAMES count=${frames.size} ${frames.joinToString()}")
        for (frameUrl in frames) {
            try {
                val frame = app.get(
                    frameUrl,
                    referer = detail.url,
                    headers = mapOf("User-Agent" to DomainResolver.UA),
                    timeout = 12,
                )
                System.out.println("[ARDASPOR] FRAME code=${frame.code} url=${frame.url}")
                if (frame.code != 200) continue
                for (stream in SportsParser.directHlsUrls(frame.text, frame.url)) {
                    if (emit(stream)) return true
                }

                // Keep old endpoint/cinema logic, but also allow it to live inside the iframe now.
                SportsParser.streamEndpoint(frame.text, channel.id)?.let { endpoint ->
                    try {
                        val response = app.get(endpoint, referer = frame.url, headers = headers, timeout = 10)
                        if (response.code == 200 && emit(SportsParser.streamResponse(response.text))) return true
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                SportsParser.cinemaRequest(frame.text, channel.id)?.let { request ->
                    try {
                        val response = app.post(request.url, referer = frame.url, headers = headers, json = request.body, timeout = 10)
                        if (response.code == 200 && emit(SportsParser.streamResponse(response.text))) return true
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.out.println("[ARDASPOR] FRAME_FAIL ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Legacy fallback on the outer page.
        SportsParser.streamEndpoint(detail.text, channel.id)?.let { endpoint ->
            try {
                val response = app.get(endpoint, referer = detail.url, headers = headers, timeout = 10)
                if (response.code == 200 && emit(SportsParser.streamResponse(response.text))) return true
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        SportsParser.cinemaRequest(detail.text, channel.id)?.let { request ->
            try {
                val response = app.post(request.url, referer = detail.url, headers = headers, json = request.body, timeout = 10)
                if (response.code == 200 && emit(SportsParser.streamResponse(response.text))) return true
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }

        // The /channel/watch page returns HTTP 200, but the real HLS is created only
        // after its JavaScript/player runs. A plain app.get() cannot see that runtime
        // network request. Hand the exact selected player frame to Baba Burda's existing
        // browser resolver instead of guessing a stream filename/domain.
        val runtimeFrame = frames.firstOrNull()
        if (!runtimeFrame.isNullOrBlank()) {
            System.out.println("[ARDASPOR_V3] WEBVIEW_HANDOFF channel=${channel.id} url=$runtimeFrame")
            callback(
                newExtractorLink(
                    source = name,
                    name = "${ChannelBranding.forChannel(channel).title} • Browser",
                    url = runtimeFrame,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    referer = detail.url
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to DomainResolver.UA,
                        "Referer" to detail.url,
                        "Origin" to siteOrigin,
                        "X-PARS-WEBVIEW" to "1",
                        "X-PARS-DETAIL-REFERER" to detail.url,
                    )
                }
            )
            return true
        }

        throw ErrorLoadingException("ArdaSpor oynatıcı iframe'i bulunamadı. ARDASPOR_V3 logunu gönderin.")
    }
}
