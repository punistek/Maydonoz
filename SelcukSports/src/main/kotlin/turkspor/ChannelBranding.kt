package turkspor

/** Human-facing channel identity is independent of the site's internal stream key. */
object ChannelBranding {
    private const val TR = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/"
    private const val BEIN = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/"
    data class Brand(val title: String, val logo: String)
    private val channels: Map<String, Brand> = buildMap {
        for (i in 1..5) put("selcukbeinsports$i", Brand("beIN Sports $i", "${BEIN}bein-sports-$i-hz-int.png"))
        for (i in 1..2) put("selcukbeinsportsmax$i", Brand("beIN Sports Max $i", "${BEIN}bein-sports-$i-max-hz-int.png"))
        put("selcukbeinsportshaber", Brand("beIN Sports Haber", "${TR}bein-sports-haber-hz-tr.png"))
        put("selcukssport", Brand("S Sport", "${TR}s-sport-tr.png"))
        put("selcukssport2", Brand("S Sport 2", "${TR}s-sport-2-tr.png"))
        for (i in 1..3) put("selcukssportplus$i", Brand("S Sport Plus $i", "${TR}s-sport-plus-tr.png"))
        put("selcuksmartspor", Brand("Spor Smart", "${TR}spor-smart-hd-tr.png"))
        put("selcuksmartspor2", Brand("Spor Smart 2", "https://i.imgur.com/qyUKCUa.png"))
        val tivibu = listOf("qvrKQY3", "qvrKQY3", "fZMSjNE", "xLrgt2O", "LgGxe7z")
        for (i in 0..4) {
            val suffix = if (i == 0) "" else i.toString()
            put("selcuktivibuspor$suffix", Brand("Tivibu Spor${if (i == 0) "" else " $i"}", "https://i.imgur.com/${tivibu[i]}.png"))
        }
        for (i in 0..7) {
            val suffix = if (i == 0) "" else i.toString()
            put("selcuktabiispor$suffix", Brand("tabii Spor${if (i == 0) "" else " $i"}", "https://cdn.prod.website-files.com/658da28123ee3a39812a40fd/65b199f7f21447f8e9e76a47_tabii-wc.png"))
        }
        put("selcukaspor", Brand("A Spor", "${TR}a-spor-tr.png"))
        put("selcuktrtspor", Brand("TRT Spor", "${TR}trt-spor-tr.png"))
        put("selcuktrtspor2", Brand("TRT Spor Yıldız", "${TR}trt-spor-yildiz-tr.png"))
        put("selcuktrtavaz", Brand("TRT Avaz", "${TR}trt-avaz-tr.png"))
        put("selcuktrt1", Brand("TRT 1", "${TR}trt-1-tr.png"))
    }
    fun forChannel(channel: SportsChannel): Brand = channels[channel.id] ?: Brand(channel.title, "")
}
