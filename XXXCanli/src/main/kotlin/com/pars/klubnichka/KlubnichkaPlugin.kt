package com.pars.klubnichka

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KlubnichkaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KlubnichkaProvider())
    }
}
