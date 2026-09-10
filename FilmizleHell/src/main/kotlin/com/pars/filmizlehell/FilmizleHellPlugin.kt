package com.pars.filmizlehell

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmizleHellPlugin : Plugin() {

    override fun load(context: android.content.Context) {
        registerMainAPI(FilmizleHell())
        registerExtractorAPI(PlayTurkaExtractor())
    }
}
