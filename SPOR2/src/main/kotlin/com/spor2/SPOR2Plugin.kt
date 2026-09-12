package com.spor2
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class SPOR2Plugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(SPOR2()) }
}
