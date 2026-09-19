package com.pars.plugins

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HuhuTRPlugin : Plugin() {
    override fun load(context: Context) {
        val snapshot = runCatching {
            context.assets.open("huhu_turkey_snapshot.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()

        registerMainAPI(HuhuTRProvider(snapshot))
    }
}
