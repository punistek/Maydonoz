// JetFilmizle.kt
// JetFilmizle -> /jetplayer -> VideoPark iframe resolver
//
// Amaç:
// 1) Film detay sayfasını aç
// 2) film_id değerini çıkar
// 3) /jetplayer'a POST at
// 4) dönen iframe src değerini çıkar
// 5) videopark.top/oplayer bağlantısını VideoParkResolver'a gönder
//
// NOT:
// - WebView yok.
// - RapidVid / FullHDFilmizle kodlarına dokunmaz.
// - Loglar özellikle ayrıntılı tutuldu.
// - Cookie/token değerleri güvenlik için loglarda TAM basılmaz.

package com.lagradost.cloudstream3.plugins

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.util.UUID

object JetFilmizleResolver {

    private const val TAG = "JET_RESOLVER"
    private const val MAIN_URL = "https://jetfilmizle.now"

    // Analizden doğrulanan kaynak indexleri:
    // 0 = Vip
    // 1 = OPlay
    // 2 = OkRu
    // 3 = STape
    // 4 = StreamHLS
    private const val DEFAULT_SOURCE_INDEX = 1
    private const val DEFAULT_PLAYER_TYPE = "dublaj"

    /**
     * JetFilmizle film detay URL'sinden doğrudan oynatılabilir link üretir.
     *
     * @param filmUrl örn: https://jetfilmizle.now/film/av-zamani
     * @param sourceIndex varsayılan OPlay = 1
     * @param playerType dublaj / altyazili / genel
     */
    suspend fun resolve(
        filmUrl: String,
        sourceIndex: Int = DEFAULT_SOURCE_INDEX,
        playerType: String = DEFAULT_PLAYER_TYPE,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trace = shortTrace()
        logI(trace, "========== JETFİLMİZLE RESOLVE BAŞLADI ==========")
        logI(trace, "filmUrl=$filmUrl")
        logI(trace, "sourceIndex=$sourceIndex playerType=$playerType")

        return try {
            // ------------------------------------------------------------
            // AŞAMA 1: Film detay sayfasını aç
            // ------------------------------------------------------------
            logI(trace, "[1/5] Film detay sayfası GET başlıyor")

            val detailResponse = app.get(
                filmUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "$MAIN_URL/"
                )
            )

            logI(trace, "[1/5] GET tamamlandı status=${detailResponse.code}")
            logI(trace, "[1/5] finalUrl=${detailResponse.url}")

            val detailHtml = detailResponse.text
            logI(trace, "[1/5] htmlLength=${detailHtml.length}")
            logPreview(trace, "[1/5] htmlPreview", detailHtml)

            if (isCloudflareBlock(detailHtml)) {
                logE(trace, "[1/5] CLOUDFLARE BLOCK tespit edildi")
                return false
            }

            // ------------------------------------------------------------
            // AŞAMA 2: film_id çıkar
            // ------------------------------------------------------------
            logI(trace, "[2/5] film_id aranıyor")

            val detailDoc = Jsoup.parse(detailHtml, filmUrl)

            val filmId = detailDoc
                .selectFirst("input[name=film_id]")
                ?.attr("value")
                ?.trim()
                .orEmpty()

            logI(trace, "[2/5] filmId='$filmId'")

            if (filmId.isBlank()) {
                logE(trace, "[2/5] film_id BULUNAMADI")
                return false
            }

            // ------------------------------------------------------------
            // AŞAMA 3: /jetplayer POST
            // ------------------------------------------------------------
            val jetPlayerUrl = "$MAIN_URL/jetplayer"

            logI(trace, "[3/5] jetplayer POST başlıyor")
            logI(
                trace,
                "[3/5] POST body: film_id=$filmId&source_index=$sourceIndex&player_type=$playerType"
            )

            val jetResponse = app.post(
                jetPlayerUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to MAIN_URL,
                    "Referer" to filmUrl
                ),
                data = mapOf(
                    "film_id" to filmId,
                    "source_index" to sourceIndex.toString(),
                    "player_type" to playerType
                )
            )

            logI(trace, "[3/5] POST tamamlandı status=${jetResponse.code}")
            logI(trace, "[3/5] finalUrl=${jetResponse.url}")

            val playerCode = jetResponse.text
            logI(trace, "[3/5] responseLength=${playerCode.length}")
            logPreview(trace, "[3/5] playerCode", playerCode)

            if (isCloudflareBlock(playerCode)) {
                logE(trace, "[3/5] jetplayer CLOUDFLARE BLOCK döndürdü")
                return false
            }

            // ------------------------------------------------------------
            // AŞAMA 4: iframe URL çıkar
            // ------------------------------------------------------------
            logI(trace, "[4/5] iframe aranıyor")

            val playerDoc = Jsoup.parse(playerCode, jetPlayerUrl)
            val iframe = playerDoc.selectFirst("iframe[src]")
            val iframeUrl = iframe?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
                ?: iframe?.attr("src")?.trim().orEmpty()

            logI(trace, "[4/5] iframeUrl=$iframeUrl")

            if (iframeUrl.isBlank()) {
                logE(trace, "[4/5] iframe BULUNAMADI")
                return false
            }

            if (!iframeUrl.contains("videopark.top", ignoreCase = true)) {
                logE(trace, "[4/5] Beklenen VideoPark iframe değil: $iframeUrl")
                return false
            }

            // ------------------------------------------------------------
            // AŞAMA 5: VideoPark resolver
            // ------------------------------------------------------------
            logI(trace, "[5/5] VideoParkResolver çağrılıyor")

            val ok = VideoParkResolver.resolve(
                embedUrl = iframeUrl,
                pageReferer = filmUrl,
                traceParent = trace,
                subtitleCallback = subtitleCallback,
                callback = callback
            )

            logI(trace, "[5/5] VideoParkResolver result=$ok")
            logI(trace, "========== JETFİLMİZLE RESOLVE BİTTİ ==========")
            ok

        } catch (t: Throwable) {
            logThrowable(trace, "GENEL HATA", t)
            false
        }
    }

    private fun isCloudflareBlock(body: String): Boolean {
        val s = body.lowercase()
        return s.contains("sorry, you have been blocked") ||
            s.contains("attention required! | cloudflare") ||
            s.contains("cf-error-details")
    }

    private fun shortTrace(): String =
        UUID.randomUUID().toString().replace("-", "").take(8)

    private fun logI(trace: String, msg: String) {
        Log.i(TAG, "[$trace] $msg")
    }

    private fun logE(trace: String, msg: String) {
        Log.e(TAG, "[$trace] $msg")
    }

    private fun logPreview(trace: String, title: String, text: String, max: Int = 700) {
        val cleaned = text
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val preview = if (cleaned.length > max) cleaned.take(max) + "..." else cleaned
        Log.d(TAG, "[$trace] $title=$preview")
    }

    private fun logThrowable(trace: String, where: String, t: Throwable) {
        Log.e(
            TAG,
            "[$trace] $where type=${t::class.java.simpleName} msg=${t.message}",
            t
        )
    }
}
