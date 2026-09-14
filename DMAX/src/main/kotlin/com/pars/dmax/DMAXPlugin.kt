package com.pars.dmax

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DMAXPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DMAX())
    }
}
