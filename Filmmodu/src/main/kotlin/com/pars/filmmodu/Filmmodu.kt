package com.pars.filmmodu

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Filmmodu : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "Filmmodu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/filmizle/turkce-dublaj-filmler-hd-izle" to "Türkçe Dublaj",
        "$mainUrl/filmizle/yerli-film-izle" to "Yerli Filmler",
        "$mainUrl/filmizle/yabanci-filmler-izle-hd" to "Yabancı Filmler",
        "$mainUrl/filmizle/1080p-filmler-hd-izle" to "1080p Filmler",
        "$mainUrl/filmizle/4k-filmler-izle" to "4K Filmler",
        "$mainUrl/filmizle/imdb-puani-yuksek-filmler" to "IMDb Puanı Yüksek Filmler",
    )

    private fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base.trimEnd('/') else "${base.trimEnd('/')}/$page"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page), referer = "$mainUrl/").document
        val items = doc.select("article.movie_box > section > a.image")
            .mapNotNull { it.toMovieSearch() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toMovieSearch(): SearchResponse? {
        val href = attr("href").trim().takeIf { it.contains("/film/") }?.let(::fixUrl) ?: return null
        val title = selectFirst(".info .title h2")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: attr("title").removeSuffix(" izle").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst("picture img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }?.let(::fixUrlNull)
        val year = selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().length < 3) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/arama/$q", referer = "$mainUrl/").document
        val cards = doc.select("article.movie_box > section > a.image")
            .mapNotNull { it.toMovieSearch() }
        if (cards.isNotEmpty()) return cards
        return doc.select("a.search_s[href*='/film/']").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return@mapNotNull null
            val title = a.selectFirst(".detail .title")?.text()?.trim()
                ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let(::fixUrlNull)
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst(".watch_top .sng_titles h1 a")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" (")?.trim()
            ?: return null
        val poster = doc.selectFirst(".movie_meta picture img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }?.let(::fixUrlNull)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrlNull)
        val description = doc.selectFirst(".movie_meta .detail .desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = doc.selectFirst(".watch_top .meta .detail > span")?.text()?.trim()?.toIntOrNull()
            ?: doc.selectFirst(".movie_meta a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select(".movie_meta a[rel='category tag']")
            .map { it.text().replace(" Filmleri", "").trim() }.filter { it.isNotBlank() }.distinct()
        val actors = doc.select(".movie_meta a[href*='/oyuncu/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val duration = Regex("""(\\d+)\\s*saat(?:\\s*(\\d+)\\s*dakika)?""", RegexOption.IGNORE_CASE)
            .find(doc.selectFirst(".watch_top .meta .detail")?.text().orEmpty())?.let {
                it.groupValues[1].toIntOrNull()?.times(60)?.plus(it.groupValues.getOrNull(2)?.toIntOrNull() ?: 0)
            }
        val score = doc.selectFirst(".watch_top .points .puan")?.let { p ->
            if (p.selectFirst("b")?.text()?.contains("IMDb", true) == true) p.selectFirst("span")?.text() else null
        }?.let { Score.from10(it) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.score = score
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val embeds = linkedSetOf<String>()

        // Gerçek iframe varsa al; Filmmodu'nun geçici ?vr_set=1 iframe'ini player sayma.
        doc.select("#f_player iframe[src], iframe[src]").forEach { frame ->
            val u = fixUrl(frame.attr("src"))
            if (!u.contains("filmmodu.cc/?vr_set=1") && !u.contains("youtube.com/embed/")) embeds += u
        }

        // Sayfa kaynaklarındaki base64 iframe parçalarını çöz.
        embeds += decodeIframeCandidates(doc.html())

        var emitted = false
        for (embed in embeds) {
            if (resolveBePlayer(embed, data, subtitleCallback, callback)) emitted = true
            if (!emitted) {
                loadExtractor(embed, data, subtitleCallback) {
                    emitted = true
                    callback(it)
                }
            }
        }
        return emitted
    }

    private fun decodeIframeCandidates(html: String): Set<String> {
        val out = linkedSetOf<String>()
        val candidates = Regex("""(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{32,}={0,2}""").findAll(html)
        for (m in candidates) {
            val raw = m.value
            val variants = listOf(raw, "PGlmcmFtZSB" + raw)
            for (v in variants) {
                val decoded = runCatching { String(Base64.decode(v, Base64.DEFAULT)) }.getOrNull() ?: continue
                Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(decoded).forEach { hit ->
                        val u = fixUrl(hit.groupValues[1].replace("\\/", "/"))
                        if (u.startsWith("http") && !u.contains("filmmodu.cc/?vr_set=1")) out += u
                    }
            }
        }
        return out
    }

    private data class JsString(val value: String, val next: Int)

    private fun readJsString(text: String, start: Int): JsString? {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || (text[i] != '\'' && text[i] != '"')) return null
        val quote = text[i++]
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            if (c == quote) return JsString(sb.toString(), i)
            if (c != '\\') { sb.append(c); continue }
            if (i >= text.length) break
            when (val e = text[i++]) {
                'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = text.substring(i, (i + 4).coerceAtMost(text.length))
                    if (hex.length == 4) { hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }; i += 4 }
                }
                else -> sb.append(e)
            }
        }
        return null
    }

    private fun bePlayerArgs(text: String): Pair<String, String>? {
        var from = 0
        while (true) {
            val p = text.indexOf("bePlayer", from, ignoreCase = true)
            if (p < 0) return null
            val open = text.indexOf('(', p + 8)
            if (open < 0) return null
            val a = readJsString(text, open + 1)
            if (a == null) { from = p + 8; continue }
            var i = a.next
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ',') { from = p + 8; continue }
            val b = readJsString(text, i + 1)
            if (b != null && b.value.contains("\"ct\"")) return a.value to b.value
            from = p + 8
        }
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ArrayList<Byte>(48)
        var previous = ByteArray(0)
        while (output.size < 48) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()
            previous.forEach { output.add(it) }
        }
        val all = output.toByteArray()
        return all.copyOfRange(0, 32) to all.copyOfRange(32, 48)
    }

    private fun hexBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching { ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    private fun decryptBePlayer(password: String, cipherJson: String): JSONObject? = runCatching {
        val obj = JSONObject(cipherJson.replace("\\/", "/"))
        val ct = Base64.decode(obj.getString("ct"), Base64.DEFAULT)
        val salt = hexBytes(obj.optString("s")) ?: return@runCatching null
        if (salt.isEmpty()) return@runCatching null
        val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
    }.getOrNull()

    private suspend fun resolveBePlayer(
        embed: String,
        parent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = runCatching { app.get(embed, referer = parent).text }.getOrNull() ?: return false
        val (password, encrypted) = bePlayerArgs(text) ?: return false
        val cfg = decryptBePlayer(password, encrypted) ?: return false

        val subtitles = cfg.optJSONArray("strSubtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val file = sub.optString("file").replace("\\/", "/")
                if (file.isBlank()) continue
                val label = sub.optString("label").ifBlank { sub.optString("language").ifBlank { "Subtitle" } }
                subtitleCallback(SubtitleFile(label, fixUrl(file, embed)))
            }
        }

        val media = cfg.optString("video_location").replace("\\/", "/")
        if (media.isBlank()) return false
        val finalUrl = fixUrl(media, embed)
        callback(
            newExtractorLink(
                source = "Filmmodu VidMixi",
                name = "Filmmodu VidMixi",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embed
                quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
