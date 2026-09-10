package turkspor

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class SportsChannel(val id: String, val title: String, val category: String, val player: String, val time: String)

/** Only parses inert HTML. Never executes site JavaScript, ads or popup handlers. */
object SportsParser {
    private val hostPattern = Regex("^(www\\.)?selcuksportshd[a-z0-9]*\\.(xyz|is)$", RegexOption.IGNORE_CASE)
    fun siteUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            !hostPattern.matches(uri.host ?: "")) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()

    fun gatewayTargets(html: String, base: String): List<String> = Jsoup.parse(html, base)
        .select("a[href]").mapNotNull { siteUrl(it.absUrl("href")) }.distinct()

    fun channels(html: String, base: String): List<SportsChannel> {
        val doc = Jsoup.parse(html, base)
        if (!doc.title().contains("SelcukSports", true)) return emptyList()
        // Deliberately exclude event tabs: the user wants permanent channels only.
        val categories = linkedMapOf("tab5" to "Spor Kanalları")
        return categories.flatMap { (tab, category) ->
            doc.select("#$tab a[data-url]").mapNotNull { a ->
                val title = a.selectFirst(".name")?.text()?.trim().orEmpty()
                val player = a.attr("data-url").substringBefore('#')
                val uri = runCatching { URI(player) }.getOrNull() ?: return@mapNotNull null
                if (uri.scheme != "https" || uri.host == null || uri.userInfo != null) return@mapNotNull null
                val id = queryParam(player, "id") ?: return@mapNotNull null
                if (title.isEmpty() || !Regex("[A-Za-z0-9_-]{1,100}").matches(id)) return@mapNotNull null
                SportsChannel(id, title, category, player, a.selectFirst("time")?.text().orEmpty())
            }.distinctBy { it.title to it.id }
        }
    }

    fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()

    fun streamUrl(html: String, player: String): String? {
        val base = Regex("""this\.baseStreamUrl\s*=\s*['"](https://[^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host == null || uri.userInfo != null) return null
        // A private stream already supplies its complete URL; do not append a channel twice.
        if (Regex("""(?:const|let|var)\s+privateStream\s*=\s*1\b""").containsMatchIn(html)) return base
        val id = queryParam(player, "id") ?: return null
        if (!Regex("[A-Za-z0-9_-]{1,100}").matches(id)) return null
        // Fail visibly if the site's URL construction changes instead of guessing an endpoint.
        if (!html.contains("/playlist.m3u8")) return null
        return "${base.trimEnd('/')}/$id/playlist.m3u8"
    }
}
