package turkspor

import org.junit.Assert.*
import org.junit.Test

class ChannelBrandingTest {
    @Test fun allObservedChannelsHaveIndividualIdentity() {
        val items = SportsParser.channels(javaClass.getResource("/selcuk-page.html")!!.readText(), "https://www.selcuksportshd123.xyz/")
        for (item in items) {
            val brand = ChannelBranding.forChannel(item)
            assertTrue(item.id, brand.logo.startsWith("https://"))
            assertFalse(brand.title, brand.title.contains("selcuk", true))
            assertFalse(brand.logo.contains("selcuksportshd"))
        }
        assertEquals("beIN Sports 1", ChannelBranding.forChannel(items.first { it.id == "selcukbeinsports1" }).title)
        assertEquals("S Sport", ChannelBranding.forChannel(items.first { it.id == "selcukssport" }).title)
        assertEquals("TRT Spor Yıldız", ChannelBranding.forChannel(items.first { it.id == "selcuktrtspor2" }).title)
    }
}
