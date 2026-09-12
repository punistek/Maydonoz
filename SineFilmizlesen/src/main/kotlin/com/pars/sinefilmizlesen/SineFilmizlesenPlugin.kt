package com.pars.sinefilmizlesen

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SineFilmizlesenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SineFilmizlesen())
    }
}
