package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class FilmMakinesiCloseLoad : ExtractorApi() {

    override val name = "FilmMakinesi CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"

    private fun baseNName(value: Int, base: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value < base) return chars[value].toString()

        var n = value
        var out = ""
        while (n > 0) {
            out = chars[n % base] + out
            n /= base
        }
        return out.ifBlank { "0" }
    }

    /**
     * Dean Edwards P.A.C.K.E.R.
     * Resolver Lab V18'de doğrulanan player önce bu katmandan açılıyor.
     */
    private fun unpackPacker(html: String): List<String> {
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\(['"]\|['"]\)\s*,\s*0\s*,\s*\{\}\s*\)\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        return regex.findAll(html).mapNotNull { m ->
            val payloadRaw = m.groupValues[2]
            val base = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val count = m.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            val words = m.groupValues[6].split("|")

            val payload = payloadRaw
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\/", "/")

            val table = HashMap<String, String>()
            for (i in 0 until minOf(count, words.size)) {
                if (words[i].isNotBlank()) {
                    table[baseNName(i, base)] = words[i]
                }
            }

            Regex("""\b\w+\b""").replace(payload) { token ->
                table[token.value] ?: token.value
            }
        }.toList()
    }

    private fun functionBody(doc: String, name: String): String {
        val start = Regex(
            """\bfunction\s+${Regex.escape(name)}\s*\([^)]*\)\s*\{""",
            RegexOption.IGNORE_CASE
        ).find(doc) ?: return ""

        var i = start.range.last + 1
        val bodyStart = i
        var depth = 1
        var quote: Char? = null
        var escaped = false

        while (i < doc.length) {
            val ch = doc[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    quote = null
                }
            } else {
                when (ch) {
                    '\'', '"', '`' -> quote = ch
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return doc.substring(bodyStart, i)
                    }
                }
            }
            i++
        }
        return ""
    }

    private fun stringList(raw: String): List<String> {
        val regex = Regex("""["']((?:\\.|[^"'\\])*)["']""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(raw).map {
            it.groupValues[1]
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
        }.toList()
    }

    private fun caesarAscii(text: String, shift: Int): String {
        val s = ((shift % 26) + 26) % 26
        return buildString(text.length) {
            text.forEach { c ->
                append(
                    when (c) {
                        in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + s) % 26).toChar()
                        in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + s) % 26).toChar()
                        else -> c
                    }
                )
            }
        }
    }

    private fun atobLatin1(text: String): String {
        var raw = text.trim()
        raw += "=".repeat((4 - raw.length % 4) % 4)
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        return bytes.toString(Charsets.ISO_8859_1)
    }

    /**
     * Current CloseLoad/HDF complex family:
     * reverse operation chain -> deterministic shuffle -> XOR stream.
     * Sabit URL kullanmaz; fonksiyondaki key/ops/mod değerlerini runtime'da çıkarır.
     */
    private fun decodeComplex(functionBody: String, parts: List<String>): String? {
        val keyOps = Regex(
            """=\s*["']([^"']{8,})["']\s*;\s*var\s+[A-Za-z_$][\w$]*\s*=\s*["']([A-Za-z]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(functionBody) ?: return null

        val key = keyOps.groupValues[1]
        val ops = keyOps.groupValues[2]
        if (ops.isBlank() || ops.any { it != 'b' && it != 'v' && it !in 'A'..'Z' }) return null

        val modH = Regex(
            """\*\s*31\s*\+\s*[A-Za-z_$][\w$]*\)\s*%\s*(\d+)"""
        ).find(functionBody)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val seedMatch = Regex(
            """\*\s*256\s*\+\s*[A-Za-z_$][\w$]*\)\s*%\s*(\d+)\)\s*\+\s*(\d+)"""
        ).find(functionBody) ?: return null

        val modSeed = seedMatch.groupValues[1].toIntOrNull() ?: return null
        val seedAdd = seedMatch.groupValues[2].toIntOrNull() ?: return null

        val triples = Regex(
            """\*\s*(\d+)\s*\+\s*(\d+)\)\s*%\s*(\d+)"""
        ).findAll(functionBody).map {
            Triple(
                it.groupValues[1].toInt(),
                it.groupValues[2].toInt(),
                it.groupValues[3].toInt()
            )
        }.toList()

        val shuffle = triples.firstOrNull { it.first != 31 && it.first != 256 }
            ?: return null

        var joined = parts.joinToString("")
        var hmm = 0
        var k81 = 0

        key.forEachIndexed { index, ch ->
            val d = ch.code
            hmm = (hmm * 31 + d) % modH
            k81 = (k81 xor (d + index)) and 255
        }

        val irz = (hmm + k81) % 256
        val ogg = (hmm % 13) + 3
        var wcy = ((hmm * 256 + k81) % modSeed) + seedAdd

        for (op in ops.reversed()) {
            joined = when {
                op == 'b' -> atobLatin1(joined)
                op == 'v' -> joined.reversed()
                op in 'A'..'Z' -> caesarAscii(joined, (26 - ((op.code - 64) % 26)) % 26)
                else -> joined
            }
        }

        val chars = joined.toCharArray()
        val fw = IntArray(chars.size)

        for (i in chars.lastIndex downTo 1) {
            wcy = (wcy * shuffle.first + shuffle.second) % shuffle.third
            fw[i] = wcy % (i + 1)
        }

        for (i in 1 until chars.size) {
            val j = fw[i]
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }

        var yr = irz
        val out = CharArray(chars.size)
        chars.forEachIndexed { i, ch ->
            val d = ch.code
            yr = (yr + ogg) % 256
            out[i] = (d xor yr).toChar()
            yr = (yr + d) % 256
        }

        return String(out)
    }

    /**
     * İkinci gözlenen HDF/Rapidrame ailesi.
     */
    private fun decodeSimple(functionBody: String, parts: List<String>): String? {
        val shifts = Regex("""\+\s*(\d+)\)\s*%\s*26""")
            .findAll(functionBody)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()

        val seedMatch = Regex(
            """\(\s*(\d+)\s*%\s*\(\s*[A-Za-z_$][\w$]*\s*\+\s*(\d+)\s*\)\s*\)"""
        ).find(functionBody) ?: return null

        if (shifts.size < 2) return null

        val seed = seedMatch.groupValues[1].toLongOrNull() ?: return null
        val offset = seedMatch.groupValues[2].toIntOrNull() ?: return null

        var joined = parts.joinToString("")
        joined = caesarAscii(joined, shifts[0])
        joined = joined.reversed()
        joined = caesarAscii(joined, shifts[1])
        joined = atobLatin1(joined)

        return buildString(joined.length) {
            joined.forEachIndexed { index, ch ->
                val sub = (seed % (index + offset)).toInt()
                val value = ((ch.code - sub) % 256 + 256) % 256
                append(value.toChar())
            }
        }
    }

    private fun decodeVariable(doc: String, variable: String): String? {
        val assignment = Regex(
            """(?:var|let|const)?\s*${Regex.escape(variable)}\s*=\s*([A-Za-z_$][\w$]*)\s*\(\s*\[(.*?)\]\s*\)\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(doc) ?: return null

        val functionName = assignment.groupValues[1]
        val parts = stringList(assignment.groupValues[2])
        if (parts.isEmpty()) return null

        val body = functionBody(doc, functionName)
        if (body.isBlank()) return null

        val complex = runCatching { decodeComplex(body, parts) }.getOrNull()
        if (!complex.isNullOrBlank()) {
            Log.i("FILMMAKINESI_CL", "DECODER complex function=$functionName parts=${parts.size}")
            return complex
        }

        val simple = runCatching { decodeSimple(body, parts) }.getOrNull()
        if (!simple.isNullOrBlank()) {
            Log.i("FILMMAKINESI_CL", "DECODER simple function=$functionName parts=${parts.size}")
            return simple
        }

        return null
    }

    private fun resolveMedia(html: String): String? {
        val docs = buildList {
            add(html)
            addAll(unpackPacker(html))
        }

        for (doc in docs) {
            val sourceVars = Regex(
                """sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z_$][\w$]*)""",
                RegexOption.IGNORE_CASE
            ).findAll(doc).map { it.groupValues[1] }.toList()

            for (variable in sourceVars) {
                if (variable.equals("atob", true) || variable.equals("btoa", true)) continue

                val decoded = decodeVariable(doc, variable)
                    ?.replace("\\/", "/")
                    ?.replace("&amp;", "&")
                    ?.trim()
                    ?.trim('\'', '"')
                    ?: continue

                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    Log.i("FILMMAKINESI_CL", "DECODED host=${runCatching { URI(decoded).host }.getOrNull()}")
                    return decoded
                }
            }
        }

        return null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer.orEmpty()

        Log.i("FILMMAKINESI_CL", "START embed=$url")

        val response = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                ),
                referer = pageReferer
            )
        }.getOrElse {
            Log.e("FILMMAKINESI_CL", "EMBED GET FAIL ${it.message}")
            return
        }

        Log.i("FILMMAKINESI_CL", "EMBED status=${response.code} chars=${response.text.length}")

        val media = resolveMedia(response.text)
        if (media.isNullOrBlank()) {
            Log.e("FILMMAKINESI_CL", "FINAL MEDIA bulunamadi")
            return
        }

        /*
         * V18 kanıtı: URL .txt ile bitse bile cevap application/vnd.apple.mpegurl
         * ve gövde #EXTM3U. Uzantıya göre MP4 sanmıyoruz.
         */
        val playbackHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to url,
            "Origin" to "${URI(url).scheme}://${URI(url).host}"
        )

        val probe = runCatching {
            app.get(
                media,
                headers = playbackHeaders,
                referer = url
            )
        }.getOrElse {
            Log.e("FILMMAKINESI_CL", "MEDIA PROBE FAIL ${it.message}")
            return
        }

        val isHls = probe.text.trimStart().startsWith("#EXTM3U")
        Log.i(
            "FILMMAKINESI_CL",
            "MEDIA status=${probe.code} ct=${probe.headers["Content-Type"]} isHls=$isHls"
        )

        if (!isHls) {
            Log.e("FILMMAKINESI_CL", "FINAL REJECT #EXTM3U yok")
            return
        }

        callback(
            newExtractorLink(
                source = "FilmMakinesi - CloseLoad",
                name = "FilmMakinesi HLS",
                url = media,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = playbackHeaders
                this.quality = Qualities.Unknown.value
            }
        )

        Log.i("FILMMAKINESI_CL", "EMIT OK")
    }
}
