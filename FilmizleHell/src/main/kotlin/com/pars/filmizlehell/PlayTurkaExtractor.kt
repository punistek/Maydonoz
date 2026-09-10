package com.pars.filmizlehell

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class PlayTurkaExtractor : ExtractorApi() {

    override val name = "PlayTurka"
    override val mainUrl = "https://p.playturka.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val cleanUrl = url
            .replace("&amp;", "&")
            .trim()

        /*
         * Örnek:
         * https://p.playturka.space/#BnMnxDFc
         *
         * veya:
         * https://p.playturka.space/?visitor=...#BnMnxDFc
         *
         * Gerçek video ID fragment (#) sonrasında.
         */
        val videoId = cleanUrl
            .substringAfterLast("#", "")
            .substringBefore("?")
            .trim()
            .takeWhile {
                it.isLetterOrDigit() || it == '-' || it == '_'
            }

        if (videoId.isBlank()) {
            Log.e(
                TAG,
                "VIDEO_ID_NOT_FOUND url=$cleanUrl"
            )
            return null
        }

        /*
         * Tarayıcıda yakaladığımız gerçek yapı:
         *
         * https://p.playturka.space/videos/BnMnxDFc/master.m3u8
         *
         * ?v=1787319294 gibi kısım cache-buster.
         * Sabit olmadığı için eklemiyoruz.
         */
        val masterUrl =
            "$mainUrl/videos/$videoId/master.m3u8"

        Log.i(
            TAG,
            "RESOLVE videoId=$videoId master=$masterUrl"
        )

        return listOf(
            newExtractorLink(
                source = name,
                name = "FilmizleHell • PlayTurka",
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to cleanUrl,
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
                )

                quality = Qualities.Unknown.value
            }
        )
    }

    companion object {
        private const val TAG = "FILMIZLEHELL_PLAYTURKA"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
