package turkspor.arda

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class SportsChannel(val id: String, val title: String, val category: String, val player: String, val time: String = "")
data class CinemaRequest(val url: String, val body: Map<String,String>)

object SportsParser {
    fun siteUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1,443) || !Regex("(www\\.)?(ardaspor|atomsportv)[0-9]+\\.top").matches(uri.host ?: "")) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()
    fun httpsUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1,443)) value else null
    }.getOrNull()
    fun gatewayTargets(html: String, base: String): List<String> = Jsoup.parse(html, base).select("a[href]").mapNotNull {
        val url = it.absUrl("href")
        siteUrl(url) ?: runCatching {
            val uri = URI(url)
            if (uri.scheme in listOf("http","https") && uri.userInfo == null && uri.port == -1 && Regex("freelink[0-9]+\\.online").matches(uri.host ?: "") && uri.path == "/ardatv")
                "https://${uri.host}/ardatv" else null
        }.getOrNull()
    }.distinct()
    fun channels(html: String, base: String): List<SportsChannel> {
        val doc = Jsoup.parse(html, base)
        return doc.select("#t2KanalInner .t2-kanal-kart[data-kanal], a.single-match[data-matchtype=tv][href]").mapNotNull { el ->
            val id = el.attr("data-kanal").ifEmpty { queryParam(el.absUrl("href"),"id").orEmpty() }
            if (!Regex("[a-z0-9-]{1,80}").matches(id)) return@mapNotNull null
            val title = el.attr("title").ifEmpty { el.selectFirst(".home")?.text().orEmpty() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            SportsChannel(id,title,"Spor Kanalları","${base}matches?id=${URLEncoder.encode(id,"UTF-8")}")
        }.distinctBy { it.id }
    }
    fun isSource(html: String) = Jsoup.parse(html).title().let { it.contains("Ardaspor",true) || it.contains("AtomSporTV",true) }
    fun channelEndpoint(html: String): String? = Regex("""fetch\(['"](https://[^'"]+/channels\.php)['"]""").find(html)?.groupValues?.get(1)?.let(::httpsUrl)
    fun streamEndpoint(html: String, id: String): String? {
        val base = Regex("""fetch\(['"](https://[^'"]+/yayinlink\.php\?id=)['"]""").find(html)?.groupValues?.get(1) ?: return null
        return httpsUrl(base + URLEncoder.encode(id,"UTF-8"))
    }
    fun cinemaRequest(html: String, id: String): CinemaRequest? {
        val url = Regex("""fetch\(['"](https://[^'"]+/cinema)['"]""").find(html)?.groupValues?.get(1)?.let(::httpsUrl) ?: return null
        val block = Regex("""body\s*:\s*JSON\.stringify\(\{(.*?)\}\)""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val body = mutableMapOf<String,String>()
        for (key in listOf("AppId","AppVer","VpcVer","Language","Token")) {
            body[key] = Regex("""\b$key\s*:\s*['"]([^'"]*)['"]""").find(block)?.groupValues?.get(1) ?: return null
        }
        body["VideoId"] = id
        return CinemaRequest(url,body)
    }
    fun streamResponse(json: String): String? = runCatching {
        val row = ObjectMapper().readTree(json)
        httpsUrl(row.path("deismackanal").asText()) ?: httpsUrl(row.path("URL").asText())
    }.getOrNull()
    fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}
