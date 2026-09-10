package com.pars.plugins

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SPOR1Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SPOR1())
    }
}
