package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

object VideoPark {

    private const val TAG = "VIDEOPARK_RESOLVER"
    private const val MAIN_URL = "https://videopark.top"

    suspend fun resolve(
        embedUrl: String,
        pageReferer: String,
        playerLabel: String,
        trace: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "[$trace] ========================================")
        Log.i(TAG, "[$trace] VIDEOPARK START")
        Log.i(TAG, "[$trace] embedUrl=${safeUrlForLog(embedUrl)}")
        Log.i(TAG, "[$trace] pageReferer=$pageReferer")
        Log.i(TAG, "[$trace] playerLabel=$playerLabel")

        return try {
            Log.i(TAG, "[$trace] [VP 1/5] embed GET")

            val response = app.get(
                embedUrl,
                headers = mapOf(
                    "Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to pageReferer,
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/153.0.0.0 Safari/537.36"
                )
            )

            Log.i(
                TAG,
                "[$trace] [VP 1/5] status=${response.code} finalUrl=${response.url}"
            )

            val html = response.text

            Log.i(
                TAG,
                "[$trace] [VP 1/5] htmlLength=${html.length}"
            )

            logPreview(trace, "[VP 1/5] HTML", html)

            if (html.isBlank()) {
                Log.e(TAG, "[$trace] [VP 1/5] HTML BOS")
                return false
            }

            Log.i(TAG, "[$trace] [VP 2/5] VIDEO_DATA aranıyor")

            val jsonText = extractVideoDataJson(html)

            if (jsonText.isNullOrBlank()) {
                Log.e(
                    TAG,
                    "[$trace] [VP 2/5] VIDEO_DATA BULUNAMADI"
                )
                return false
            }

            Log.i(
                TAG,
                "[$trace] [VP 2/5] VIDEO_DATA length=${jsonText.length}"
            )

            logPreview(
                trace,
                "[VP 2/5] VIDEO_DATA",
                jsonText
            )

            val root = JSONObject(jsonText)

            val title = root.optString("title")
            val duration = root.optString("duration")

            val hlsObject = root.optJSONObject("hlsSource")
            val hlsFile = hlsObject
                ?.optString("file")
                ?.trim()
                .orEmpty()

            Log.i(
                TAG,
                "[$trace] [VP 3/5] title='$title' duration='$duration'"
            )

            Log.i(
                TAG,
                "[$trace] [VP 3/5] hlsFile=${safeUrlForLog(hlsFile)}"
            )

            var emittedAny = false

            if (hlsFile.isNotBlank()) {
                emittedAny = emitHls(
                    trace = trace,
                    hlsFile = hlsFile,
                    title = title,
                    playerLabel = playerLabel,
                    callback = callback
                ) || emittedAny
            } else {
                Log.e(
                    TAG,
                    "[$trace] [VP 3/5] hlsSource.file BOS"
                )
            }

            val mp4Sources = root.optJSONArray("mp4Sources")

            emittedAny = emitMp4Fallbacks(
                trace = trace,
                array = mp4Sources,
                playerLabel = playerLabel,
                callback = callback
            ) || emittedAny

            Log.i(
                TAG,
                "[$trace] [VP 5/5] END emittedAny=$emittedAny"
            )
            Log.i(TAG, "[$trace] ========================================")

            emittedAny
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "[$trace] VIDEOPARK EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            false
        }
    }

    private suspend fun emitHls(
        trace: String,
        hlsFile: String,
        title: String,
        playerLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "[$trace] [VP 4/5] HLS doğrulama GET")

        val response = try {
            app.get(
                hlsFile,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to MAIN_URL,
                    "Referer" to "$MAIN_URL/"
                )
            )
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "[$trace] [VP 4/5] HLS GET FAIL type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            null
        }

        if (response == null) {
            return false
        }

        val body = response.text
        val isM3u8 = body.trimStart().startsWith("#EXTM3U")

        Log.i(
            TAG,
            "[$trace] [VP 4/5] HLS status=${response.code} len=${body.length} isM3u8=$isM3u8"
        )

        logPreview(
            trace,
            "[VP 4/5] HLS PREVIEW",
            body,
            500
        )

        if (!isM3u8) {
            Log.e(
                TAG,
                "[$trace] [VP 4/5] HLS URL geldi ama #EXTM3U DEGIL"
            )
            return false
        }

        val prettyLabel =
            if (playerLabel.isBlank()) {
                "OPlay HLS"
            } else {
                "OPlay HLS [$playerLabel]"
            }

        callback.invoke(
            newExtractorLink(
                source = "JetFilmizle - OPlay",
                name = prettyLabel,
                url = hlsFile,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$MAIN_URL/"
                this.headers = mapOf(
                    "Origin" to MAIN_URL,
                    "Referer" to "$MAIN_URL/"
                )
                this.quality = Qualities.Unknown.value
            }
        )

        Log.i(
            TAG,
            "[$trace] [VP 4/5] HLS CALLBACK OK url=${safeUrlForLog(hlsFile)}"
        )

        return true
    }

    private fun emitMp4Fallbacks(
        trace: String,
        array: JSONArray?,
        playerLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (array == null) {
            Log.i(
                TAG,
                "[$trace] [VP 5/5] mp4Sources YOK"
            )
            return false
        }

        Log.i(
            TAG,
            "[$trace] [VP 5/5] mp4Sources count=${array.length()}"
        )

        var emitted = false

        for (i in 0 until array.length()) {
            try {
                val item = array.optJSONObject(i) ?: continue

                val file = item.optString("file").trim()
                val label = item.optString("label", "MP4").trim()
                val height = item.optInt("height", 0)

                Log.i(
                    TAG,
                    "[$trace] [VP 5/5] mp4[$i] label='$label' height=$height url=${safeUrlForLog(file)}"
                )

                if (file.isBlank()) continue

                val name =
                    if (playerLabel.isBlank()) {
                        "OPlay $label"
                    } else {
                        "OPlay $label [$playerLabel]"
                    }

                callback.invoke(
                    newExtractorLink(
                        source = "JetFilmizle - OPlay",
                        name = name,
                        url = file,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$MAIN_URL/"
                        this.headers = mapOf(
                            "Origin" to MAIN_URL,
                            "Referer" to "$MAIN_URL/"
                        )
                        this.quality =
                            if (height > 0) {
                                height
                            } else {
                                qualityFromLabel(label)
                            }
                    }
                )

                emitted = true
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "[$trace] [VP 5/5] mp4[$i] ERROR type=${t::class.java.simpleName} msg=${t.message}",
                    t
                )
            }
        }

        return emitted
    }

    private fun qualityFromLabel(label: String): Int {
        return Regex("""(\d{3,4})""")
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    /**
     * const VIDEO_DATA = {...};
     *
     * JSON nested olduğu için basit .*? regex yerine
     * dengeli { } taraması yapıyoruz.
     */
    private fun extractVideoDataJson(html: String): String? {
        val marker = "const VIDEO_DATA"
        val markerIndex = html.indexOf(marker)

        if (markerIndex < 0) {
            return null
        }

        val equalsIndex = html.indexOf(
            '=',
            startIndex = markerIndex + marker.length
        )

        if (equalsIndex < 0) {
            return null
        }

        val start = html.indexOf(
            '{',
            startIndex = equalsIndex + 1
        )

        if (start < 0) {
            return null
        }

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
                        return html.substring(
                            start,
                            i + 1
                        )
                    }
                }
            }
        }

        return null
    }

    private fun logPreview(
        trace: String,
        title: String,
        text: String,
        max: Int = 900
    ) {
        val cleaned = text
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val preview =
            if (cleaned.length > max) {
                cleaned.take(max) + "...[len=${cleaned.length}]"
            } else {
                cleaned
            }

        Log.d(
            TAG,
            "[$trace] $title=$preview"
        )
    }

    private fun safeUrlForLog(url: String): String {
        if (url.isBlank()) return "<empty>"
        if (url.length <= 180) return url

        return url.take(110) +
            "...[len=${url.length}]..." +
            url.takeLast(35)
    }
}
