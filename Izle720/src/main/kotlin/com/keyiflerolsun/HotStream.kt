package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HotStream : ExtractorApi() {

    override val name = "HotStream"
    override val mainUrl = "https://hotstream.club"
    override val requiresReferer = true

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"

    private data class KeyIv(
        val key: ByteArray,
        val iv: ByteArray
    )

    /**
     * CryptoJS passphrase mode:
     * AES-256-CBC + OpenSSL EVP_BytesToKey MD5 chaining.
     */
    private fun evpBytesToKey(
        passphrase: ByteArray,
        salt: ByteArray,
        keyLength: Int = 32,
        ivLength: Int = 16
    ): KeyIv {
        val needed = keyLength + ivLength
        val output = ArrayList<Byte>(needed)
        var previous = ByteArray(0)

        while (output.size < needed) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(passphrase)
            md5.update(salt)

            previous = md5.digest()

            previous.forEach { output.add(it) }
        }

        val all = output.toByteArray()

        return KeyIv(
            key = all.copyOfRange(0, keyLength),
            iv = all.copyOfRange(
                keyLength,
                keyLength + ivLength
            )
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) {
            "Invalid hex length"
        }

        return ByteArray(hex.length / 2) { index ->
            hex.substring(
                index * 2,
                index * 2 + 2
            ).toInt(16).toByte()
        }
    }

    private fun decryptCryptoJs(
        passphrase: String,
        encryptedJson: String
    ): String {
        val json = JSONObject(
            encryptedJson.replace("\\/", "/")
        )

        val cipherText = json.getString("ct")
        val saltHex = json.optString("s")

        require(saltHex.isNotBlank()) {
            "CryptoJS salt missing"
        }

        val salt = hexToBytes(saltHex)

        val keyIv = evpBytesToKey(
            passphrase.toByteArray(Charsets.UTF_8),
            salt
        )

        val cipherBytes =
            Base64.getDecoder().decode(cipherText)

        val cipher =
            Cipher.getInstance("AES/CBC/PKCS5Padding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyIv.key, "AES"),
            IvParameterSpec(keyIv.iv)
        )

        return cipher
            .doFinal(cipherBytes)
            .toString(Charsets.UTF_8)
    }

    /**
     * Verified current page shape:
     *
     * bePlayer(
     *   'PASSWORD',
     *   '{"ct":"...","iv":"...","s":"..."}'
     * )
     */
    private fun extractBePlayer(
        html: String
    ): Pair<String, String>? {
        val regex = Regex(
            """bePlayer\s*\(\s*(['"])(.*?)\1\s*,\s*(['"])(\{.*?\})\3\s*(?:,\s*[^)]*)?\)""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )

        val match = regex.find(html) ?: return null

        val password =
            match.groupValues[2].trim()

        val encryptedJson =
            match.groupValues[4]
                .replace("\\/", "/")

        if (
            password.isBlank() ||
            encryptedJson.isBlank()
        ) {
            return null
        }

        return password to encryptedJson
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer.orEmpty()

        Log.i(
            "HOTSTREAM",
            "START embed=$url referer=$pageReferer"
        )

        val embed = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to
                        "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                ),
                referer = pageReferer
            )
        }.getOrElse {
            Log.e(
                "HOTSTREAM",
                "EMBED GET FAIL ${it::class.simpleName}: ${it.message}"
            )
            return
        }

        Log.i(
            "HOTSTREAM",
            "EMBED status=${embed.code} chars=${embed.text.length}"
        )

        val player = extractBePlayer(embed.text)

        if (player == null) {
            Log.e(
                "HOTSTREAM",
                "bePlayer call bulunamadi"
            )
            return
        }

        val (password, encryptedJson) = player

        Log.i(
            "HOTSTREAM",
            "BEPLAYER FOUND passwordLen=${password.length} jsonLen=${encryptedJson.length}"
        )

        val plaintext = runCatching {
            decryptCryptoJs(
                password,
                encryptedJson
            )
        }.getOrElse {
            Log.e(
                "HOTSTREAM",
                "DECRYPT FAIL ${it::class.simpleName}: ${it.message}"
            )
            return
        }

        Log.i(
            "HOTSTREAM",
            "DECRYPT OK chars=${plaintext.length}"
        )

        val params = runCatching {
            JSONObject(plaintext)
        }.getOrElse {
            Log.e(
                "HOTSTREAM",
                "DECRYPT JSON FAIL ${it::class.simpleName}: ${it.message}"
            )
            return
        }

        val videoLocation =
            params.optString("video_location")
                .trim()
                .replace("\\/", "/")

        if (videoLocation.isBlank()) {
            Log.e(
                "HOTSTREAM",
                "video_location BOS"
            )
            return
        }

        val hlsUrl =
            if (
                videoLocation.startsWith("http://") ||
                videoLocation.startsWith("https://")
            ) {
                videoLocation
            } else {
                java.net.URI(url)
                    .resolve(videoLocation)
                    .toString()
            }

        Log.i(
            "HOTSTREAM",
            "VIDEO_LOCATION host=" +
                runCatching {
                    java.net.URI(hlsUrl).host
                }.getOrNull()
        )

        /*
         * Important verified behaviour:
         * bare /list/... may return 404.
         * The same URL becomes HLS only with embed Referer + Hotstream Origin.
         */
        val playbackHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to url
        )

        val playlist = runCatching {
            app.get(
                hlsUrl,
                headers = playbackHeaders,
                referer = url
            )
        }.getOrElse {
            Log.e(
                "HOTSTREAM",
                "HLS GET FAIL ${it::class.simpleName}: ${it.message}"
            )
            return
        }

        val body = playlist.text
        val isHls =
            body.trimStart().startsWith("#EXTM3U")

        Log.i(
            "HOTSTREAM",
            "HLS status=${playlist.code} " +
                "ct=${playlist.headers["Content-Type"]} " +
                "chars=${body.length} isHls=$isHls"
        )

        if (!isHls) {
            Log.e(
                "HOTSTREAM",
                "FINAL REJECT: #EXTM3U yok"
            )
            return
        }

        callback(
            newExtractorLink(
                source = "720izle - HotStream",
                name = "720izle HLS",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = playbackHeaders
                this.quality = Qualities.Unknown.value
            }
        )

        Log.i(
            "HOTSTREAM",
            "EMIT OK"
        )
    }
}
