package com.pars.plugins.test2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TestPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TestProvider())
    }
}
