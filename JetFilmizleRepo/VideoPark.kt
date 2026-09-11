// VideoPark.kt
// videopark.top/oplayer/?id=... -> VIDEO_DATA -> hlsSource.file
//
// Doğrulanan yapı:
// const VIDEO_DATA = {
//   ...
//   "hlsSource": {
//      "file":"https://ok.erikkalinina1994.workers.dev/m/<TOKEN>",
//      "type":"hls",
//      "label":"Auto"
//   },
//   ...
// }
//
// /m/<TOKEN> cevabı #EXTM3U playlist.
// /p/<TOKEN> yolları segment/proxy kaynakları.
//
// NOT:
// - VIDEO_DATA içindeki MP4 kaliteleri fallback olarak da çıkarılır.
// - Hassas uzun tokenlar loglarda kısaltılır.

package com.lagradost.cloudstream3.plugins

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

object VideoParkResolver {

    private const val TAG = "VIDEOPARK_RESOLVER"
    private const val VIDEOPARK = "https://videopark.top"

    private val mapper by lazy { jacksonObjectMapper() }

    suspend fun resolve(
        embedUrl: String,
        pageReferer: String,
        traceParent: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trace = traceParent ?: shortTrace()

        logI(trace, "========== VIDEOPARK RESOLVE BAŞLADI ==========")
        logI(trace, "embedUrl=$embedUrl")
        logI(trace, "pageReferer=$pageReferer")

        return try {
            // ------------------------------------------------------------
            // AŞAMA 1: OPlayer HTML GET
            // ------------------------------------------------------------
            logI(trace, "[VP 1/5] embed GET başlıyor")

            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to pageReferer
                )
            )

            logI(trace, "[VP 1/5] GET status=${response.code}")
            logI(trace, "[VP 1/5] finalUrl=${response.url}")

            val html = response.text
            logI(trace, "[VP 1/5] htmlLength=${html.length}")
            logPreview(trace, "[VP 1/5] htmlPreview", html)

            if (html.isBlank()) {
                logE(trace, "[VP 1/5] HTML boş")
                return false
            }

            // ------------------------------------------------------------
            // AŞAMA 2: const VIDEO_DATA = {...}; çıkar
            // ------------------------------------------------------------
            logI(trace, "[VP 2/5] VIDEO_DATA aranıyor")

            val jsonText = extractVideoDataJson(html)

            if (jsonText == null) {
                logE(trace, "[VP 2/5] VIDEO_DATA BULUNAMADI")
                return false
            }

            logI(trace, "[VP 2/5] VIDEO_DATA bulundu length=${jsonText.length}")
            logPreview(trace, "[VP 2/5] VIDEO_DATA preview", jsonText)

            // ------------------------------------------------------------
            // AŞAMA 3: JSON parse
            // ------------------------------------------------------------
            logI(trace, "[VP 3/5] VIDEO_DATA JSON parse")

            val root = mapper.readTree(jsonText)

            val title = root.path("title").asText("")
            val duration = root.path("duration").asText("")

            logI(trace, "[VP 3/5] title='$title' duration='$duration'")

            val hlsFile = root
                .path("hlsSource")
                .path("file")
                .asText("")
                .trim()

            logI(trace, "[VP 3/5] hlsFile=${safeUrl(hlsFile)}")

            // ------------------------------------------------------------
            // AŞAMA 4: HLS doğrulama
            // ------------------------------------------------------------
            var emitted = false

            if (hlsFile.isNotBlank()) {
                logI(trace, "[VP 4/5] HLS playlist doğrulanıyor")

                val hlsCheck = try {
                    app.get(
                        hlsFile,
                        headers = mapOf(
                            "Referer" to "$VIDEOPARK/",
                            "Origin" to VIDEOPARK,
                            "Accept" to "*/*"
                        )
                    )
                } catch (t: Throwable) {
                    logThrowable(trace, "[VP 4/5] HLS GET HATA", t)
                    null
                }

                if (hlsCheck != null) {
                    val body = hlsCheck.text
                    logI(trace, "[VP 4/5] HLS status=${hlsCheck.code}")
                    logI(trace, "[VP 4/5] HLS bodyLength=${body.length}")
                    logPreview(trace, "[VP 4/5] HLS preview", body, 400)

                    val isM3u8 = body.trimStart().startsWith("#EXTM3U")

                    logI(trace, "[VP 4/5] isM3u8=$isM3u8")

                    if (isM3u8) {
                        callback(
                            newExtractorLink(
                                source = "JetFilmizle - OPlay",
                                name = if (title.isNotBlank()) "OPlay HLS - $title" else "OPlay HLS",
                                url = hlsFile,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$VIDEOPARK/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "Referer" to "$VIDEOPARK/",
                                    "Origin" to VIDEOPARK
                                )
                            }
                        )

                        emitted = true
                        logI(trace, "[VP 4/5] HLS CALLBACK gönderildi")
                    } else {
                        logE(trace, "[VP 4/5] URL geldi ama içerik #EXTM3U değil")
                    }
                }
            } else {
                logE(trace, "[VP 4/5] hlsSource.file boş")
            }

            // ------------------------------------------------------------
            // AŞAMA 5: MP4 fallback kaliteleri
            // ------------------------------------------------------------
            logI(trace, "[VP 5/5] MP4 fallback taranıyor")

            val mp4Sources = root.path("mp4Sources")

            if (mp4Sources.isArray) {
                logI(trace, "[VP 5/5] mp4Sources count=${mp4Sources.size()}")

                mp4Sources.forEachIndexed { index, node ->
                    try {
                        val file = node.path("file").asText("").trim()
                        val label = node.path("label").asText("MP4")
                        val height = node.path("height").asInt(0)

                        logI(
                            trace,
                            "[VP 5/5] mp4[$index] label=$label height=$height url=${safeUrl(file)}"
                        )

                        if (file.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    source = "JetFilmizle - OPlay",
                                    name = "OPlay $label",
                                    url = file,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$VIDEOPARK/"
                                    this.quality = if (height > 0) height else Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to "$VIDEOPARK/",
                                        "Origin" to VIDEOPARK
                                    )
                                }
                            )

                            emitted = true
                        }
                    } catch (t: Throwable) {
                        logThrowable(trace, "[VP 5/5] mp4[$index] parse/callback HATA", t)
                    }
                }
            } else {
                logI(trace, "[VP 5/5] mp4Sources array değil / yok")
            }

            logI(trace, "emitResult=$emitted")
            logI(trace, "========== VIDEOPARK RESOLVE BİTTİ ==========")

            emitted

        } catch (t: Throwable) {
            logThrowable(trace, "VIDEOPARK GENEL HATA", t)
            false
        }
    }

    /**
     * "const VIDEO_DATA = {...};" içindeki JSON nesnesini dengeli parantez taramasıyla çıkarır.
     * Regex ile .*? kullanmıyoruz; nested array/object olduğundan kırılabilir.
     */
    private fun extractVideoDataJson(html: String): String? {
        val marker = "const VIDEO_DATA"
        val markerIndex = html.indexOf(marker)

        if (markerIndex < 0) return null

        val equalsIndex = html.indexOf('=', markerIndex + marker.length)
        if (equalsIndex < 0) return null

        val start = html.indexOf('{', equalsIndex + 1)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until html.length) {
            val c = html[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }

                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return html.substring(start, i + 1)
                    }
                }
            }
        }

        return null
    }

    private fun safeUrl(url: String): String {
        if (url.isBlank()) return "<empty>"

        // Çok uzun tokenı tamamen loglamıyoruz.
        if (url.length <= 140) return url

        return url.take(95) + "...[len=${url.length}]..." + url.takeLast(25)
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
