package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

object JetGlobal {

    private const val TAG = "JETGLOBAL_RESOLVER"
    private const val API_BASE = "https://videopark.top/jetembed/backend/api_sources.php"
    private const val PLAYER_BASE = "https://videopark.top"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Safari/537.36"

    suspend fun resolve(
        embedUrl: String,
        detailUrl: String,
        trace: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val imdb = Regex("""tt\d{6,12}""", RegexOption.IGNORE_CASE)
            .find(embedUrl)
            ?.value
            ?.lowercase()
            .orEmpty()

        if (imdb.isBlank()) {
            Log.e(TAG, "[$trace] JETGLOBAL imdb BULUNAMADI embed=${safeUrlForLog(embedUrl)}")
            return false
        }

        val apiUrl = "$API_BASE?imdb=$imdb"

        Log.i(TAG, "[$trace] ========================================")
        Log.i(TAG, "[$trace] JETGLOBAL START imdb=$imdb")
        Log.i(TAG, "[$trace] embed=${safeUrlForLog(embedUrl)}")
        Log.i(TAG, "[$trace] api=$apiUrl")

        return try {
            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Referer" to embedUrl,
                    "User-Agent" to UA,
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )

            val body = response.text.trim()

            Log.i(
                TAG,
                "[$trace] API status=${response.code} finalUrl=${response.url} bodyLength=${body.length}"
            )

            if (body.isBlank()) {
                Log.e(TAG, "[$trace] API BODY BOS")
                return false
            }

            val root = JSONObject(body)
            val success = root.optBoolean("success", false)
            val sources = root.optJSONArray("sources")

            Log.i(
                TAG,
                "[$trace] API parsed success=$success total=${root.optInt("total", sources?.length() ?: 0)} timeout=${root.optInt("timeout_seconds", 0)}"
            )

            if (!success || sources == null || sources.length() == 0) {
                Log.e(TAG, "[$trace] KAYNAK YOK")
                return false
            }

            data class Source(
                val id: Int,
                val name: String,
                val url: String,
                val priority: Int,
                val successRate: Int
            )

            val parsed = buildList {
                for (i in 0 until sources.length()) {
                    val item = sources.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim().replace("\\/", "/")
                    if (url.isBlank()) continue

                    add(
                        Source(
                            id = item.optInt("id", -1),
                            name = item.optString("name", "source-$i").trim().ifBlank { "source-$i" },
                            url = url,
                            priority = item.optInt("priority_score", 0),
                            successRate = item.optInt("success_rate", 0)
                        )
                    )
                }
            }
                .distinctBy { it.url }
                .sortedWith(
                    compareByDescending<Source> { it.priority }
                        .thenByDescending { it.successRate }
                )

            parsed.forEachIndexed { i, source ->
                Log.i(
                    TAG,
                    "[$trace] SOURCE[$i] id=${source.id} name='${source.name}' priority=${source.priority} successRate=${source.successRate} host='${hostOf(source.url)}' url=${safeUrlForLog(source.url)}"
                )
            }

            var totalEmitted = 0

            for ((index, source) in parsed.withIndex()) {
                var sourceEmitted = 0

                val sourceCallback: (ExtractorLink) -> Unit = { link ->
                    sourceEmitted += 1
                    totalEmitted += 1

                    Log.i(
                        TAG,
                        "[$trace] CALLBACK source='${source.name}' emitted=$sourceEmitted url=${safeUrlForLog(link.url)}"
                    )

                    callback(link)
                }

                Log.i(
                    TAG,
                    "[$trace] TRY[$index] source='${source.name}' priority=${source.priority} url=${safeUrlForLog(source.url)}"
                )

                try {
                    val reported = loadExtractor(
                        source.url,
                        embedUrl,
                        subtitleCallback,
                        sourceCallback
                    )

                    Log.i(
                        TAG,
                        "[$trace] TRY[$index] GENERIC reported=$reported emitted=$sourceEmitted source='${source.name}'"
                    )
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "[$trace] TRY[$index] GENERIC FAIL source='${source.name}' type=${t::class.java.simpleName} msg=${t.message}"
                    )
                }

                // Bazı aggregator kaynakları doğrudan extractor ile eşleşmez ama
                // sayfa içinde asıl iframe'i taşır. Callback gelmediyse bir katman
                // HTML açıp gerçek iframe'i generic extractor'a veriyoruz.
                if (sourceEmitted == 0) {
                    try {
                        val page = app.get(
                            source.url,
                            headers = mapOf(
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                "Referer" to embedUrl,
                                "User-Agent" to UA
                            )
                        )

                        val doc = org.jsoup.Jsoup.parse(page.text, source.url)
                        val nested = doc.select("iframe[src]")
                            .mapNotNull { iframe ->
                                iframe.absUrl("src").trim()
                                    .ifBlank { iframe.attr("src").trim() }
                                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            }
                            .distinct()

                        Log.i(
                            TAG,
                            "[$trace] TRY[$index] NESTED count=${nested.size} source='${source.name}'"
                        )

                        for ((nestedIndex, nestedUrl) in nested.withIndex()) {
                            if (hostOf(nestedUrl).endsWith("videopark.top")) continue

                            val before = sourceEmitted

                            try {
                                val nestedReported = loadExtractor(
                                    nestedUrl,
                                    source.url,
                                    subtitleCallback,
                                    sourceCallback
                                )

                                Log.i(
                                    TAG,
                                    "[$trace] TRY[$index] NESTED[$nestedIndex] reported=$nestedReported emitted=${sourceEmitted - before} host='${hostOf(nestedUrl)}'"
                                )
                            } catch (t: Throwable) {
                                Log.w(
                                    TAG,
                                    "[$trace] TRY[$index] NESTED[$nestedIndex] FAIL type=${t::class.java.simpleName} msg=${t.message}"
                                )
                            }

                            if (sourceEmitted > 0) break
                        }
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "[$trace] TRY[$index] PAGE FAIL source='${source.name}' type=${t::class.java.simpleName} msg=${t.message}"
                        )
                    }
                }

                // Mevcut uygulama ilk gerçek callback'i seçiyor. Bir JetGlobal
                // provider gerçekten oynatılabilir link ürettiyse diğer 11 kaynağı
                // gereksiz yere taramayalım.
                if (sourceEmitted > 0) {
                    Log.i(
                        TAG,
                        "[$trace] JETGLOBAL SUCCESS source='${source.name}' emitted=$sourceEmitted totalEmitted=$totalEmitted"
                    )
                    Log.i(TAG, "[$trace] ========================================")
                    return true
                }
            }

            Log.w(TAG, "[$trace] JETGLOBAL END emitted=0")
            Log.i(TAG, "[$trace] ========================================")
            false
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "[$trace] JETGLOBAL EXCEPTION type=${t::class.java.simpleName} msg=${t.message}",
                t
            )
            false
        }
    }

    private fun hostOf(url: String): String {
        return try {
            java.net.URI(url).host.orEmpty().lowercase()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun safeUrlForLog(url: String, keep: Int = 150): String {
        if (url.length <= keep * 2) return url
        return url.take(keep) + "...[len=${url.length}]..." + url.takeLast(keep)
    }
}
