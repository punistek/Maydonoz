package turkspor

import org.junit.Assert.*
import org.junit.Test

class SportsParserTest {
    private val site = "https://www.selcuksportshd123.xyz/"
    private fun channel(id: String, name: String) = """<a data-url="https://player.example/index.php?id=$id#poster=ignored"><div class="name">$name</div></a>"""
    @Test fun onlyPermanentChannelsAreListed() {
        val html = """<title>SelcukSportsHD</title><div id="tab1">${channel("s1", "Team A - Team B")}</div><div id="tab5">${channel("s1", "S Sport")}${channel("trt", "TRT Spor")}</div>"""
        val result = SportsParser.channels(html, site)
        assertEquals(listOf("S Sport", "TRT Spor"), result.map { it.title })
        assertTrue(result.none { it.player.contains('#') })
    }
    @Test fun undefinedChannelsAndAdsAreNotListed() {
        val html = """<title>SelcukSportsHD</title><div id="tab5"><a data-url="Undefined channel."><div class="name">Broken</div></a><a href="https://ad.example">Ad</a>${channel("real", "TRT Spor")}</div>"""
        assertEquals(1, SportsParser.channels(html, site).size)
    }
    @Test fun parkedOrImpersonatingPagesDoNotBecomeWorkingDomains() {
        assertTrue(SportsParser.channels("<title>Domain for sale</title><div id='tab5'>${channel("x", "X")}</div>", site).isEmpty())
        for (bad in listOf("https://selcuksportshd123.xyz.evil.com", "https://selcuksportshd123.xyz@evil.com", "http://selcuksportshd123.xyz", "https://127.0.0.1", "https://www.selcuksportshd123.xyz:444/")) assertNull(SportsParser.siteUrl(bad))
    }
    @Test fun gatewayOnlyAcceptsSiteFamily() {
        val links = """<a href="$site">Site Giriş</a><a href="https://ad.example/">Bonus</a><a href="https://www.xyzsports-1.xyz/">XYZ</a>"""
        assertEquals(listOf(site), SportsParser.gatewayTargets(links, DomainFixture.gateway))
    }
    @Test fun sourceUsesActualChannelIdAndCurrentCdn() {
        val script = """this.baseStreamUrl = 'https://new-cdn.example/live/'; return `/playlist.m3u8`;"""
        assertEquals("https://new-cdn.example/live/selcukbeinsports1/playlist.m3u8", SportsParser.streamUrl(script, "https://player.example/index.php?id=selcukbeinsports1"))
    }
    @Test fun privateStreamIsNotAppended() {
        assertEquals("https://cdn.example/direct.m3u8", SportsParser.streamUrl("""this.baseStreamUrl='https://cdn.example/direct.m3u8'; const privateStream = 1;""", "https://player.example/?id=unused"))
    }
    @Test fun unexpectedPlayerAndTraversalFailClosed() {
        assertNull(SportsParser.streamUrl("const unrelated='https://ad.example/ad.m3u8'", "https://player.example/?id=a"))
        assertNull(SportsParser.streamUrl("""this.baseStreamUrl='https://cdn.example/'; '/playlist.m3u8'""", "https://player.example/?id=..%2fsecret"))
    }
    @Test fun capturedLivePageContainsOnlyPermanentChannels() {
        val html = javaClass.getResource("/selcuk-page.html")!!.readText()
        val channels = SportsParser.channels(html, site)
        assertEquals(33, channels.size)
        assertTrue(channels.any { it.title == "TRT Spor" })
        assertFalse(channels.any { it.title.contains("Celta") || it.title.contains("Athletic") })
    }
}
private object DomainFixture { const val gateway = "https://www.selcuksportshd.is/" }
