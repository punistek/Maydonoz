package turkspor.arda
import org.junit.Assert.*
import org.junit.Test

class ArdaTest {
    @Test fun atomAliasExposesTheSameChannels() {
        val html=javaClass.getResource("/atom.html")!!.readText()
        assertTrue(SportsParser.isSource(html))
        assertEquals("https://www.atomsportv513.top/",SportsParser.siteUrl("https://www.atomsportv513.top/"))
        val atom=SportsParser.channels(html,"https://www.atomsportv513.top/")
        val arda=SportsParser.channels(javaClass.getResource("/arda-page.html")!!.readText(),"https://ardaspor30.top/")
        assertEquals(arda.map { it.id },atom.map { it.id })
        assertNull(SportsParser.siteUrl("https://atomsportv513.top.evil.example/"))
    }
    @Test fun channelApiAndSliderAgreeAndExcludeEvents() {
        val site = "https://ardaspor30.top/"
        val slider = SportsParser.channels(javaClass.getResource("/arda-page.html")!!.readText(),site)
        val channels = SportsParser.channels(javaClass.getResource("/arda-channels.html")!!.readText()+"""<a class="single-match" data-matchtype="football" href="matches?id=game"><div class="home">Team A</div></a>""",site)
        assertEquals(12, channels.size)
        assertEquals(slider.map { it.id },channels.map { it.id })
        assertTrue(channels.all { ChannelBranding.forChannel(it).logo.startsWith("https://") })
    }
    @Test fun actualPlayerEndpointsAndFallbackPayloadAreParsed() {
        val html=javaClass.getResource("/player.html")!!.readText()
        assertEquals("https://teletv5.top/load/yayinlink.php?id=bein-sports-1",SportsParser.streamEndpoint(html,"bein-sports-1"))
        val request=SportsParser.cinemaRequest(html,"s-sport")!!
        assertEquals("https://streamsport365.com/cinema",request.url)
        assertEquals("s-sport",request.body["VideoId"])
        assertEquals("5000",request.body["AppId"])
        assertEquals("",request.body["Token"])
    }
    @Test fun shortlinkIsUpgradedAndOnlyExpectedDomainsAreAccepted() {
        val html="""<a href="http://freelink1.online/ardatv">Giriş</a><a href="https://ads.example/">Ad</a><a href="https://ardaspor31.top">Sonraki</a>"""
        assertEquals(listOf("https://freelink1.online/ardatv","https://ardaspor31.top/"),SportsParser.gatewayTargets(html,"https://www.ardasporgiris.site/"))
        assertNull(SportsParser.siteUrl("https://ardaspor31.top.evil.com/"))
        assertNull(SportsParser.siteUrl("https://user@ardaspor31.top/"))
    }
    @Test fun streamResponseDoesNotUseAdsOrInvalidUrls() {
        assertEquals("https://cdn.example/live.m3u8",SportsParser.streamResponse("""{"deismackanal":"https://cdn.example/live.m3u8","ad":"https://ads.example/ad.m3u8"}"""))
        assertNull(SportsParser.streamResponse("""{"URL":"javascript:alert(1)"}"""))
    }
}
