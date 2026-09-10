package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FullHDFilmizlePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(FullHDFilmizle())
    }
}
