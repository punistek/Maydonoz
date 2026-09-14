package com.pars.yabancidizi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YabanciDiziPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(YabanciDizi())
    }
}
