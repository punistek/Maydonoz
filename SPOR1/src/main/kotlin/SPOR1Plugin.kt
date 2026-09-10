package com.pars.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SPOR1Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SPOR1())
    }
}
