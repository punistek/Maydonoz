package turkspor.arda

object ChannelBranding {
    private const val TR = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/turkey/"
    private const val BEIN = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/international/beinsports/old/horizontal/"
    data class Brand(val title: String,val logo: String)
    private val channels = buildMap {
        for (i in 1..5) put("bein-sports-$i",Brand("beIN Sports $i","${BEIN}bein-sports-$i-hz-int.png"))
        for (i in 1..2) put("bein-sports-max-$i",Brand("beIN Sports Max $i","${BEIN}bein-sports-$i-max-hz-int.png"))
        put("s-sport",Brand("S Sport","${TR}s-sport-tr.png"))
        put("s-sport-2",Brand("S Sport 2","${TR}s-sport-2-tr.png"))
        put("trt-spor",Brand("TRT Spor","${TR}trt-spor-tr.png"))
        put("trt-1",Brand("TRT 1","${TR}trt-1-tr.png"))
        put("a-spor",Brand("A Spor","${TR}a-spor-tr.png"))
    }
    fun forChannel(channel: SportsChannel) = channels[channel.id] ?: Brand(channel.title,"")
}
