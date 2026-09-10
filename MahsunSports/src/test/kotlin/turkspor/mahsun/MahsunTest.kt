package turkspor.mahsun
import org.junit.Assert.*
import org.junit.Test

class MahsunTest {
    private val site = "https://mahsunsports80.xyz/"
    @Test fun permanentChannelArrayExcludesMatchArrays() {
        val script = "const footballMatches = [{title:'A vs B',url:'/event.html?id=androstreamlivech123'}];" + javaClass.getResource("/channels.js")!!.readText()
        val channels = SportsParser.channels(script, site)
        assertEquals(53, channels.size)
        assertEquals("beIN Sports 1", ChannelBranding.forChannel(channels.first()).title)
        assertTrue(channels.all { ChannelBranding.forChannel(it).logo.startsWith("https://") })
        assertFalse(channels.any { it.title.contains("vs") })
    }
    @Test fun onlyChannelDataScriptIsRead() {
        val page = """<title>Mahsun Sports</title><script src="mahsunsports.js"></script><script src="https://chr0me.org/script4.js"></script>"""
        assertEquals("https://chr0me.org/script4.js", SportsParser.dataScript(page,site))
        assertNull(SportsParser.dataScript("<title>Parked</title>"+page.substringAfter("</title>"),site))
    }
    @Test fun refreshStreamIsUsedInsteadOfPlaceholder() {
        val html = javaClass.getResource("/player.html")!!.readText()
        assertEquals(listOf("https://andro.evrenesoglu99.click/checklist/androstreamlivebs1.m3u8"), SportsParser.streamUrls(html, "${site}event.html?id=androstreamlivebs1"))
        assertEquals(1, SportsParser.streamUrls(html,"${site}event.html?id=androstreamlivess1").size)
        assertTrue(SportsParser.streamUrls(html,"${site}event.html?id=..%2Fsecret").isEmpty())
    }
    @Test fun gatewayDoesNotAcceptFilmOrAdLinks() {
        val html = """<a href="https://mahsunsports81.xyz/">Maç</a><a href="https://mahsundizi.net/">Film</a><a href="https://mahsunsports81.xyz.ads.example/">Reklam</a>"""
        assertEquals(listOf("https://mahsunsports81.xyz/"), SportsParser.gatewayTargets(html,"https://mahsunsports.com/"))
    }
}
