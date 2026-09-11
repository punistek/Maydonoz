package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JetFilmizlePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(JetFilmizle())
    }
}
