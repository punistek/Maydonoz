package com.pars.dizimakinesi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziMakinesiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziMakinesi())
    }
}
