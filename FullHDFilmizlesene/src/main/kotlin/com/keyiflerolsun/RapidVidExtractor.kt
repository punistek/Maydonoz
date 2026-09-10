// ! RapidVidExtractor.kt
// RapidVid guncel akis:
// FullHD SCX -> https://rapidvid.org/vx/v1x...
// /vx sayfasi -> window._p8 -> decode -> JSON(vk/cm/tm)
// core.min JS -> H = tierSupported ? tm : cm -> JWPlayer HLS source
//
// NOT:
// /vx adresini artik /vod/ adresine CEVIRMIYORUZ.
// Tarayicida /vod/v1x...?c=1 istegi player PLAY sonrasi kontrol/telemetri amacli atiliyor.
// Asil player bootstrap verisi /vx sayfasindaki window._p8 icinde.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.json.JSONObject

open class RapidVid : ExtractorApi() {

    override val name = "RapidVid"
    override val mainUrl = "https://rapidvid.org"
    override val requiresReferer = true

    // CloudStream host APK'nin kendi Cloudflare çözümünü kullan.
    // Challenge gövdesi gelirse CloudflareKiller normal CloudStream akışını devralır.
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cloudflareInterceptor by lazy { RapidVidCloudflareInterceptor(cloudflareKiller) }

    private class RapidVidCloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(1024L * 1024L).string()
            val document = Jsoup.parse(body)

            val challenged =
                document.html().contains("Just a moment", ignoreCase = true) ||
                    body.contains("cf-chl-", ignoreCase = true) ||
                    body.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) ||
                    body.contains("Enable JavaScript and cookies to continue", ignoreCase = true)

            if (challenged) {
                Log.w("Kekik_RapidVid", "Cloudflare challenge -> CloudflareKiller")
                response.close()
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: "https://www.fullhdfilmizlesene.now/"
        val playerUrl = normalizePlayerUrl(url)

        Log.d("Kekik_RapidVid", "input     » $url")
        Log.d("Kekik_RapidVid", "playerUrl » $playerUrl")
        Log.d("Kekik_RapidVid", "referer   » $extRef")

        /*
         * Kritik:
         * Eski kod /vx/ -> /vod/ yapiyordu.
         *
         * Guncel RapidVid core JS ise decoded window._p8 icindeki:
         *   cm = t.cm
         *   tm = t.tm
         * degerlerini player source olarak kullaniyor.
         *
         * /vod/v1x{vk}?c=1 istegi core JS tarafinda PLAY sonrasi atiliyor.
         * Bu nedenle bootstrap HTML icin orijinal /vx/ sayfasini okumaliyiz.
         */
        val response = app.get(
            playerUrl,
            referer = extRef,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to DESKTOP_UA
            ),
            interceptor = cloudflareInterceptor
        )

        val html = response.text

        Log.d(
            "Kekik_RapidVid",
            "HTML status=${response.code} len=${html.length} finalUrl=${response.url}"
        )

        if (html.isBlank()) {
            throw ErrorLoadingException("RapidVid HTML bos geldi")
        }

        if (isCloudflareChallenge(html)) {
            throw ErrorLoadingException(
                "RapidVid /vx Cloudflare challenge dondu"
            )
        }

        if (html.contains("403 Forbidden", ignoreCase = true)) {
            throw ErrorLoadingException(
                "RapidVid /vx 403 Forbidden dondu"
            )
        }

        // Acik altyazi yapisi HTML icinde varsa kaybetme.
        extractSubtitles(html, subtitleCallback)

        /*
         * Guncel core.min.2026090601.js'in ilk satirlari hala:
         *
         * JSON.parse(
         *   atob(
         *     ... window._p8 ...
         *   )
         * )
         *
         * kullaniyor. Yani decode algoritmamiz dogru;
         * sorun daha once yanlis /vod sayfasini okumamizdi.
         */
        val p8 = extractP8(html)
            ?: run {
                logHtmlClues(html)
                throw ErrorLoadingException(
                    "RapidVid /vx HTML geldi fakat window._p8 bulunamadi"
                )
            }

        Log.d("Kekik_RapidVid", "_p8 bulundu » len=${p8.length}")

        val decodedJson = try {
            decodeP8(p8)
        } catch (e: Exception) {
            Log.e("Kekik_RapidVid", "_p8 decode hatasi", e)
            throw ErrorLoadingException(
                "RapidVid _p8 decode edilemedi: ${e.message}"
            )
        }

        Log.d(
            "Kekik_RapidVid",
            "_p8 JSON » ${decodedJson.take(900)}"
        )

        val data = try {
            JSONObject(decodedJson)
        } catch (e: Exception) {
            Log.e("Kekik_RapidVid", "JSON parse hatasi", e)
            throw ErrorLoadingException("RapidVid _p8 sonucu JSON degil")
        }

        val vkey = data.optString("vk", "").trim()
        val cm = data.optString("cm", "").trim()
        val tm = data.optString("tm", "").trim()

        // core JS'de tier-ready flag: D = !!t.tr
        val tierReady = data.optBoolean("tr", false)

        Log.d("Kekik_RapidVid", "vkey      » $vkey")
        Log.d("Kekik_RapidVid", "tierReady » $tierReady")
        Log.d("Kekik_RapidVid", "cm        » ${safeUrlForLog(cm)}")
        Log.d("Kekik_RapidVid", "tm        » ${safeUrlForLog(tm)}")

        /*
         * Guncel core JS:
         *
         *   var G = t.cm || ""
         *   var D = !!t.tr
         *   var J = t.tm || ""
         *   var L = D && codec-support-check
         *   var H = L ? J : G
         *   M.sources = [{ file: H, type: "hls" }]
         *
         * Android Media3 tarafinda tarayici codec probe'unu aynen
         * calistirmiyoruz. Guvenli tercih:
         *   1) cm varsa cm
         *   2) cm yoksa tm
         *
         * Boylece eski calisan davranisi koruyoruz.
         */
        val masterUrl = when {
            isHttpUrl(cm) -> cm
            isHttpUrl(tm) -> tm
            else -> throw ErrorLoadingException(
                "RapidVid HLS master bulunamadi (cm/tm bos)"
            )
        }

        Log.d(
            "Kekik_RapidVid",
            "MASTER » ${safeUrlForLog(masterUrl)}"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to DESKTOP_UA
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /**
     * RapidVid player URL'sini normalize eder.
     *
     * Kritik degisiklik:
     * /vx/ artik /vod/ yapilmaz.
     */
    private fun normalizePlayerUrl(input: String): String {
        var out = input.trim()

        if (out.startsWith("//")) {
            out = "https:$out"
        }

        out = out
            .replace("https://www.rapidvid.org", mainUrl)
            .replace("https://rapidvid.net", mainUrl)
            .replace("https://www.rapidvid.net", mainUrl)

        return out
    }

    /**
     * window._p8 = "...."
     * window._p8='....'
     * bosluk/yeni satir varyasyonlarini destekler.
     */
    private fun extractP8(html: String): String? {
        val patterns = listOf(
            Regex("""window\s*\.\s*_p8\s*=\s*"([^"]+)""""),
            Regex("""window\s*\.\s*_p8\s*=\s*'([^']+)'"""),
            Regex("""(?:window\.)?_p8\s*=\s*"([^"]+)""""),
            Regex("""(?:window\.)?_p8\s*=\s*'([^']+)'""")
        )

        for (regex in patterns) {
            val value = regex.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()

            if (!value.isNullOrBlank()) {
                return value
            }
        }

        return null
    }

    /**
     * RapidVid core JS'teki decode fonksiyonunun Kotlin karsiligi:
     *
     * 1) _p8 ters cevrilir
     * 2) atob
     * 3) K9L dongusel anahtariyla charCode shift geri alinir
     * 4) tekrar atob
     * 5) JSON
     */
    private fun decodeP8(encoded: String): String {
        require(encoded.isNotBlank()) { "_p8 bos" }

        val firstBytes = Base64.decode(
            normalizeBase64(encoded.reversed()),
            Base64.DEFAULT
        )

        val key = "K9L"
        val transformed = StringBuilder(firstBytes.size)

        for (i in firstBytes.indices) {
            val unsignedByte = firstBytes[i].toInt() and 0xFF
            val keyCode = key[i % key.length].code
            val shift = (keyCode % 5) + 1

            transformed.append(
                (unsignedByte - shift).toChar()
            )
        }

        val secondBytes = Base64.decode(
            normalizeBase64(transformed.toString()),
            Base64.DEFAULT
        )

        return secondBytes.toString(Charsets.UTF_8)
    }

    private fun normalizeBase64(value: String): String {
        var out = value
            .trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .replace('-', '+')
            .replace('_', '/')

        val remainder = out.length % 4
        if (remainder != 0) {
            out += "=".repeat(4 - remainder)
        }

        return out
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("Just a moment", ignoreCase = true) ||
            html.contains("cf-chl-", ignoreCase = true) ||
            html.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) ||
            html.contains("Enable JavaScript and cookies to continue", ignoreCase = true)
    }

    /**
     * _p8 bulunamazsa tahmin etmek yerine bize bir sonraki test icin
     * kanit verecek ipuclari logla.
     */
    private fun logHtmlClues(html: String) {
        val clues = listOf(
            "window._p8",
            "_p8",
            "core.min.",
            "jwplayer",
            "Just a moment",
            "cf-chl-",
            "403 Forbidden"
        )

        val found = clues.filter {
            html.contains(it, ignoreCase = true)
        }

        val compact = html
            .replace(Regex("""\s+"""), " ")
            .take(700)

        Log.w(
            "Kekik_RapidVid",
            "HTML_CLUES found=$found preview=$compact"
        )
    }

    private fun extractSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = mutableSetOf<String>()

        Regex(
            """"captions","file":"([^"]+)","label":"([^"]+)""""
        ).findAll(html).forEach { match ->
            val subUrl = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\", "")

            val subLang = match.groupValues[2]
                .replace("\\u0131", "ı")
                .replace("\\u0130", "İ")
                .replace("\\u00fc", "ü")
                .replace("\\u00e7", "ç")
                .replace("\\u011f", "ğ")
                .replace("\\u015f", "ş")

            if (subUrl.isBlank() || !seen.add(subUrl)) {
                return@forEach
            }

            subtitleCallback.invoke(
                SubtitleFile(
                    lang = subLang,
                    url = subUrl
                )
            )
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") ||
            value.startsWith("http://")
    }

    private fun safeUrlForLog(value: String): String {
        if (value.isBlank()) return "<bos>"

        val schemeEnd = value.indexOf("://")
        if (schemeEnd == -1) {
            return value.take(100)
        }

        val pathStart = value.indexOf('/', schemeEnd + 3)
        if (pathStart == -1) {
            return value
        }

        val nextSlash = value.indexOf('/', pathStart + 1)
        return if (nextSlash == -1) {
            value
        } else {
            value.substring(0, nextSlash) + "/..."
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"
    }
}
