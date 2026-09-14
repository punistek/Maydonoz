package turkspor.arda

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class SportsChannel(
    val id: String,
    val title: String,
    val category: String,
    val player: String,
    val time: String = "",
    val directStream: String? = null,
)

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
        val selector = listOf(
            "#t2KanalInner .t2-kanal-kart[data-kanal]",
            "a.single-match[data-matchtype=tv][href]",
            "a[href*='/mac-izle/']",
            "a[href^='mac-izle/']",
        ).joinToString(",")

        return doc.select(selector).mapNotNull { el ->
            val href = el.absUrl("href")
            val id = el.attr("data-kanal").ifBlank {
                queryParam(href, "id").orEmpty()
            }.ifBlank {
                runCatching {
                    val path = URI(href).path.orEmpty()
                    path.substringAfterLast("/mac-izle/", "").trim('/').substringBefore('/')
                }.getOrDefault("")
            }
            if (!Regex("[a-z0-9-]{1,100}").matches(id)) return@mapNotNull null

            val title = sequenceOf(
                el.attr("title"),
                el.selectFirst(".home")?.text(),
                el.selectFirst("img[alt]")?.attr("alt"),
                el.text(),
            ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()
                ?.removeSuffix(" izle")
                ?.trim()
                ?: return@mapNotNull null

            val player = if (href.isNotBlank() && runCatching { URI(href).path.contains("/mac-izle/") }.getOrDefault(false)) {
                href
            } else {
                "${base}matches?id=${URLEncoder.encode(id,"UTF-8")}"
            }

            val direct = sequenceOf("data-m3u8", "data-src", "data-stream", "data-url")
                .map { el.attr(it).trim() }
                .firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?.let { resolveHttps(base, it) }

            SportsChannel(id, title, "Spor Kanalları", player, directStream = direct)
        }.distinctBy { it.id }
    }

    fun isSource(html: String) = Jsoup.parse(html).title().let { it.contains("Ardaspor",true) || it.contains("AtomSporTV",true) }

    fun channelEndpoint(html: String): String? = Regex("""fetch\(['\"](https://[^'\"]+/channels\.php)['\"]""").find(html)?.groupValues?.get(1)?.let(::httpsUrl)

    fun streamEndpoint(html: String, id: String): String? {
        val base = Regex("""fetch\(['\"](https://[^'\"]+/yayinlink\.php\?id=)['\"]""").find(html)?.groupValues?.get(1) ?: return null
        return httpsUrl(base + URLEncoder.encode(id,"UTF-8"))
    }


    /** Player detail pages now wrap the actual player in /channel/watch/... iframe. */
    fun playerFrames(html: String, base: String): List<String> = runCatching {
        Jsoup.parse(html, base).select("iframe[src]").mapNotNull { frame ->
            val url = frame.absUrl("src").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.port !in listOf(-1,443)) return@mapNotNull null
            if (!uri.path.orEmpty().contains("/channel/watch/")) return@mapNotNull null
            url
        }.distinct()
    }.getOrDefault(emptyList())

    /**
     * New Arda pages expose the real HLS directly in page markup / inline JS.
     * This deliberately accepts only HTTPS .m3u8 URLs and resolves relative forms.
     */
    fun directHlsUrls(html: String, base: String): List<String> {
        val normalized = html
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val found = linkedSetOf<String>()
        Regex("""https://[^\s'\"<>]+?\.m3u8(?:\?[^\s'\"<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value }
            .forEach { httpsUrl(it)?.let(found::add) }

        val doc = Jsoup.parse(html, base)
        doc.select("[data-m3u8], source[src*=.m3u8], video[src*=.m3u8], a[href*=.m3u8]").forEach { el ->
            sequenceOf(el.attr("data-m3u8"), el.attr("src"), el.attr("href"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull { resolveHttps(base, it) }
                .filter { it.contains(".m3u8", true) }
                .forEach(found::add)
        }
        return found.toList()
    }

    private fun resolveHttps(base: String, value: String): String? = runCatching {
        val resolved = URI(base).resolve(value).toString()
        httpsUrl(resolved)
    }.getOrNull()

    fun cinemaRequest(html: String, id: String): CinemaRequest? {
        val url = Regex("""fetch\(['\"](https://[^'\"]+/cinema)['\"]""").find(html)?.groupValues?.get(1)?.let(::httpsUrl) ?: return null
        val block = Regex("""body\s*:\s*JSON\.stringify\(\{(.*?)\}\)""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val body = mutableMapOf<String,String>()
        for (key in listOf("AppId","AppVer","VpcVer","Language","Token")) {
            body[key] = Regex("""\b$key\s*:\s*['\"]([^'\"]*)['\"]""").find(block)?.groupValues?.get(1) ?: return null
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
