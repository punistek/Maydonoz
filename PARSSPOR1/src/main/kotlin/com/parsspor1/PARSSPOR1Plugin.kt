package com.parsspor1
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class PARSSPOR1Plugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(PARSSPOR1()) }
}
