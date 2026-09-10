package turkspor.mahsun

object ChannelBranding {
    private const val TR = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/"
    private const val BEIN = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/"
    private const val TABII = "https://cdn.prod.website-files.com/658da28123ee3a39812a40fd/65b199f7f21447f8e9e76a47_tabii-wc.png"
    private const val EXXEN = "https://upload.wikimedia.org/wikipedia/commons/d/db/Exxen.png"
    data class Brand(val title: String, val logo: String)
    private val channels = buildMap {
        for (i in 1..5) put("bs$i", Brand("beIN Sports $i", "${BEIN}bein-sports-$i-hz-int.png"))
        for (i in 1..2) put("bsm$i", Brand("beIN Sports Max $i", "${BEIN}bein-sports-$i-max-hz-int.png"))
        put("ss1", Brand("S Sport", "${TR}s-sport-tr.png"))
        put("ss2", Brand("S Sport 2", "${TR}s-sport-2-tr.png"))
        put("ssplus1", Brand("S Sport Plus", "${TR}s-sport-plus-tr.png"))
        put("sm1", Brand("Spor Smart", "${TR}spor-smart-hd-tr.png"))
        put("sm2", Brand("Spor Smart 2", "https://i.imgur.com/qyUKCUa.png"))
        val tivibu = listOf("qvrKQY3", "qvrKQY3", "fZMSjNE", "xLrgt2O", "LgGxe7z")
        for (i in 0..4) { val suffix = if(i == 0) "" else i.toString()
            put("ts$suffix", Brand("Tivibu Spor${if(i == 0) "" else " $i"}", "https://i.imgur.com/${tivibu[i]}.png")) }
        for (i in 0..8) { val suffix = if(i == 0) "" else i.toString()
            put("tb$suffix", Brand("tabii Spor${if(i == 0) "" else " $i"}", TABII))
            put("exn$suffix", Brand(if(i == 0) "Exxen" else "Exxen Sports $i", EXXEN)) }
        put("es1", Brand("Eurosport 1", "https://i.imgur.com/olQJgm7.png"))
        put("es2", Brand("Eurosport 2", "https://i.imgur.com/f56dHgR.png"))
        put("idm", Brand("İdman TV", "https://i.imgur.com/fM9FOrZ.png"))
        put("cbcs", Brand("CBC Sport", "https://i.imgur.com/3mEdjuq.png"))
        put("trt1", Brand("TRT 1", "${TR}trt-1-tr.png"))
        put("trts", Brand("TRT Spor", "${TR}trt-spor-tr.png"))
        put("trtsy", Brand("TRT Spor Yıldız", "${TR}trt-spor-yildiz-tr.png"))
        put("as", Brand("A Spor", "${TR}a-spor-tr.png"))
        put("atv", Brand("ATV", "${TR}atv-tr.png"))
        put("a2", Brand("A2", "${TR}a2-tr.png"))
        put("tjk", Brand("TJK TV", "${TR}tjk-tv-tr.png"))
        put("ht", Brand("HT Spor", "https://www.htspor.com/images/manifest/social-share-logo.png"))
        put("nba", Brand("NBA TV", "https://upload.wikimedia.org/wikipedia/en/thumb/d/d2/NBA_TV.svg/960px-NBA_TV.svg.png"))
        put("tv8", Brand("TV8", "${TR}tv8-tr.png"))
        put("tv85", Brand("TV8,5", "${TR}tv85-tr.png"))
        put("fb", Brand("FB TV", "https://i.imgur.com/qBVqtYd.png"))
        put("gs", Brand("GS TV", "https://i.imgur.com/fC3KuwT.jpg"))
        put("sptstv", Brand("Sports TV", "${TR}sports-tv-tr.png"))
    }
    fun forChannel(channel: SportsChannel) = channels[channel.id.removePrefix("androstreamlive").removePrefix("facebooklive")] ?: Brand(channel.title, "")
}
