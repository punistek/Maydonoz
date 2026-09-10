package com.lagradost.cloudstream3.plugins.tizam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TizamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Tizam())
    }
}
