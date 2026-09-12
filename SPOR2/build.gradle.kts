plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}
android {
    namespace = "com.spor2"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
}
cloudstream {
    language = "tr"
    description = "SPOR2 - Maç Listesi ve 7/24 TV"
    authors = listOf("ERN Bilisim")
    status = 1
    tvTypes = listOf("Live")
}
dependencies {
    implementation("com.github.recloudstream.cloudstream:library:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
