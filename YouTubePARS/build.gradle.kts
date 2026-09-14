version = 2

cloudstream {
    description = "YouTube: trendler, müzik, oyun, podcast, canlı yayın, arama, kanal ve playlist · PARS"
    authors = listOf("KaifTaufiq", "PARS")
    status = 3
    tvTypes = listOf("Other", "Live", "TvSeries")
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/YouTube_full-color_icon_%282017%29.svg/3840px-YouTube_full-color_icon_%282017%29.svg.png"
    isCrossPlatform = true
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")
}
