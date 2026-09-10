package turkspor.mahsun

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class SportsChannel(val id: String, val title: String, val category: String, val player: String, val time: String = "")

object SportsParser {
    private val mapper = ObjectMapper().configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true).configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
    fun siteUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1,443) || !Regex("(www\\.)?mahsunsports[0-9]*\\.(com|xyz)").matches(uri.host ?: "")) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()
    fun gatewayTargets(html: String, base: String): List<String> = Jsoup.parse(html, base)
        .select("a[href]").mapNotNull { siteUrl(it.absUrl("href")) }.distinct()
    fun httpsUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1,443)) value else null
    }.getOrNull()
    fun dataScript(html: String, base: String): String? {
        val doc = Jsoup.parse(html, base)
        if (!doc.title().contains("Mahsun", true)) return null
        return doc.select("script[src]").map { it.absUrl("src") }.firstOrNull {
            httpsUrl(it) != null && runCatching { URI(it).path.endsWith("/script4.js") }.getOrDefault(false)
        }
    }
    fun channels(script: String, base: String): List<SportsChannel> = runCatching {
        val array = Regex("""const\s+channels\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1) ?: return emptyList()
        mapper.readTree(array).mapNotNull { row ->
            val title = row.path("title").asText().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val player = URI(base).resolve(row.path("url").asText()).toString()
            if (httpsUrl(player) == null || siteUrl(player) != siteUrl(base)) return@mapNotNull null
            val id = queryParam(player, "id") ?: return@mapNotNull null
            if (!Regex("(androstreamlive|facebooklive)[a-zA-Z0-9]{1,30}").matches(id) || id.contains("livech")) return@mapNotNull null
            SportsChannel(id, title, "Spor Kanalları", player)
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    fun streamUrls(html: String, player: String): List<String> = runCatching {
        val id = queryParam(player, "id") ?: return emptyList()
        if (!Regex("(androstreamlive|facebooklive)[a-zA-Z0-9]{1,30}").matches(id)) return emptyList()
        val array = Regex("""const\s+baseurls\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return emptyList()
        if (!html.contains("getStreamSource()") || !html.contains(".m3u8")) return emptyList()
        // The site's refresh button uses real=1 for beIN 1; do not return its batutest placeholder.
        mapper.readTree(array).mapNotNull { base -> httpsUrl(base.asText())?.let { "${it.trimEnd('/')}/$id.m3u8" } }.distinct()
    }.getOrDefault(emptyList())
    fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}
