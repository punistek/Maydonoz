package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.parser.Parser

object VidMoly {

    private const val TAG = "VIDMOLY_RESOLVER"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"

    suspend fun resolve(
        embedUrl: String,
        pageReferer: String,
        playerLabel: String,
        trace: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "[$trace] VIDMOLY V9 START embed=${safeUrlForLog(embedUrl)}")

        return try {
            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to pageReferer
                )
            )

            val html = response.text
            Log.i(
                TAG,
                "[$trace] VIDMOLY GET status=${response.code} finalUrl=${response.url} htmlLength=${html.length}"
            )

            val hls = extractHls(html)
            if (hls.isNullOrBlank()) {
                Log.e(TAG, "[$trace] VIDMOLY V9 m3u8 BULUNAMADI")
                return false
            }

            Log.i(TAG, "[$trace] VIDMOLY V9 HLS url=${safeUrlForLog(hls)}")

            // URL'in kendisini de doğrula. Token süresi geçmiş / HTML dönen linki
            // player'a başarılı kaynak diye göndermeyelim.
            val verify = app.get(
                hls,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to embedUrl,
                    "Accept" to "*/*"
                )
            )
            val verifyBody = verify.text
            val isHls = verify.code in 200..299 && verifyBody.trimStart().startsWith("#EXTM3U")

            Log.i(
                TAG,
                "[$trace] VIDMOLY V9 VERIFY status=${verify.code} len=${verifyBody.length} isM3u8=$isHls"
            )

            if (!isHls) {
                Log.e(TAG, "[$trace] VIDMOLY V9 HLS doğrulama BASARISIZ")
                return false
            }

            val label = if (playerLabel.isBlank()) {
                "VidMoly HLS"
            } else {
                "VidMoly HLS [$playerLabel]"
            }

            callback(
                newExtractorLink(
                    source = "JetFilmizle - VidMoly",
                    name = label,
                    url = hls,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = embedUrl
                    headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to ua
                    )
                    quality = Qualities.Unknown.value
                }
            )

            Log.i(TAG, "[$trace] VIDMOLY V9 CALLBACK OK")
            true
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "[$trace] VIDMOLY V9 EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            false
        }
    }

    private fun extractHls(html: String): String? {
        // V8'de raw Kotlin Regex icinde \\s ve \\. kullanıldığı için regex
        // whitespace / '.m3u8' yerine literal ters slash arıyordu. V9'da
        // gerçek VidMoly yapısını doğrudan yakalıyoruz:
        // [{ "file": 'https://.../master.m3u8?...' }]
        val decoded = Parser.unescapeEntities(html, false)
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        val patterns = listOf(
            Regex("""[\"']?file[\"']?\s*:\s*[\"'](https?://[^\"']+?\.m3u8[^\"']*)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""[\"'](https?://[^\"']+?\.m3u8[^\"']*)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s\"'<>]+?\.m3u8[^\s\"'<>]*)""", RegexOption.IGNORE_CASE)
        )

        for ((index, pattern) in patterns.withIndex()) {
            val raw = pattern.find(decoded)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (raw.isBlank()) continue

            Log.i(TAG, "VIDMOLY V9 REGEX_MATCH pattern=$index")
            return raw
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
        }

        return null
    }

    private fun safeUrlForLog(url: String, keep: Int = 150): String {
        if (url.length <= keep * 2) return url
        return url.take(keep) + "...[len=${url.length}]..." + url.takeLast(keep)
    }
}
