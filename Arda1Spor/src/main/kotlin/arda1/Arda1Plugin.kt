package arda1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Arda1Plugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("arda1_domain", Context.MODE_PRIVATE)
        registerMainAPI(Arda1(DomainResolver(prefs)))
    }
}
