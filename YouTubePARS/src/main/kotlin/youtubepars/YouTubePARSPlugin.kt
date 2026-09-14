package youtubepars

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YouTubePARSPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YoutubeProvider())
    }
}