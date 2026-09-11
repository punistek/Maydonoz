package com.keyiflerolsun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class VidMixi : ExtractorApi() {
    override val name = "VidMixi"
    override val mainUrl = "https://vidmixi.com"
    override val requiresReferer = true

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    private data class KeyIv(val key: ByteArray, val iv: ByteArray)

    private fun evpBytesToKey(
        passphrase: ByteArray,
        salt: ByteArray,
        keyLength: Int = 32,
        ivLength: Int = 16
    ): KeyIv {
        val needed = keyLength + ivLength
        val out = ArrayList<Byte>(needed)
        var previous = ByteArray(0)

        while (out.size < needed) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(passphrase)
            md5.update(salt)
            previous = md5.digest()
            previous.forEach { out.add(it) }
        }

        val all = out.toByteArray()
        return KeyIv(
            key = all.copyOfRange(0, keyLength),
            iv = all.copyOfRange(keyLength, keyLength + ivLength)
        )
    }

    private fun decryptCryptoJs(
        passphrase: String,
        encryptedJson: String
    ): String {
        val obj = JSONObject(encryptedJson)

        val ct = obj.getString("ct")
        val saltHex = obj.optString("s")
        val explicitIvHex = obj.optString("iv")

        require(saltHex.isNotBlank()) { "CryptoJS salt (s) missing" }

        val salt = hexToBytes(saltHex)
        val derived = evpBytesToKey(
            passphrase = passphrase.toByteArray(Charsets.UTF_8),
            salt = salt
        )

        val cipherBytes = Base64.getDecoder().decode(ct)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(derived.key, "AES"),
            IvParameterSpec(derived.iv)
        )

        val plain = cipher.doFinal(cipherBytes)
        val text = plain.toString(Charsets.UTF_8)

        println(
            "VIDMIXI_DIAG DECRYPT_META " +
                "saltLen=${salt.size} explicitIvLen=${explicitIvHex.length} " +
                "cipherBytes=${cipherBytes.size} plainChars=${text.length}"
        )

        return text
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun extractBePlayer(html: String): Pair<String, String>? {
        val rx = Regex(
            """bePlayer\s*\(\s*(['"])(.*?)\1\s*,\s*(['"])(\{.*?\})\3\s*(?:,\s*[^)]*)?\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val match = rx.find(html) ?: return null
        val hash = match.groupValues[2].trim()
        val encrypted = match.groupValues[4].replace("\\/", "/")

        if (hash.isBlank() || encrypted.isBlank()) return null
        return hash to encrypted
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val detailReferer = referer.orEmpty()
        println("VIDMIXI_DIAG START embed=$url referer=$detailReferer")

        val embedHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        var embed = runCatching {
            val started = System.currentTimeMillis()
            println("VIDMIXI_DIAG EMBED_ATTEMPT n=1")
            val r = app.get(url, headers = embedHeaders, referer = detailReferer)
            println(
                "VIDMIXI_DIAG EMBED_ATTEMPT_OK n=1 " +
                    "ms=${System.currentTimeMillis() - started} status=${r.code}"
            )
            r
        }.onFailure {
            println(
                "VIDMIXI_DIAG EMBED_ATTEMPT_FAIL n=1 " +
                    "exception=${it::class.simpleName} message=${it.message}"
            )
        }.getOrNull()

        if (embed == null) {
            delay(900)
            embed = runCatching {
                val started = System.currentTimeMillis()
                println("VIDMIXI_DIAG EMBED_ATTEMPT n=2")
                val r = app.get(url, headers = embedHeaders, referer = detailReferer)
                println(
                    "VIDMIXI_DIAG EMBED_ATTEMPT_OK n=2 " +
                        "ms=${System.currentTimeMillis() - started} status=${r.code}"
                )
                r
            }.onFailure {
                println(
                    "VIDMIXI_DIAG EMBED_ATTEMPT_FAIL n=2 " +
                        "exception=${it::class.simpleName} message=${it.message}"
                )
            }.getOrNull()
        }

        if (embed == null) {
            delay(1500)
            embed = runCatching {
                val started = System.currentTimeMillis()
                println("VIDMIXI_DIAG EMBED_ATTEMPT n=3")
                val r = app.get(url, headers = embedHeaders, referer = detailReferer)
                println(
                    "VIDMIXI_DIAG EMBED_ATTEMPT_OK n=3 " +
                        "ms=${System.currentTimeMillis() - started} status=${r.code}"
                )
                r
            }.onFailure {
                println(
                    "VIDMIXI_DIAG EMBED_ATTEMPT_FAIL n=3 " +
                        "exception=${it::class.simpleName} message=${it.message}"
                )
            }.getOrNull()
        }

        if (embed == null) {
            println("VIDMIXI_DIAG FAIL stage=EMBED reason=ALL_ATTEMPTS_FAILED")
            return
        }

        val html = embed.text
        println(
            "VIDMIXI_DIAG EMBED status=${embed.code} final=${embed.url} " +
                "contentType=${embed.headers["Content-Type"]} chars=${html.length}"
        )

        val player = extractBePlayer(html)
        if (player == null) {
            println("VIDMIXI_DIAG FAIL stage=BEPLAYER reason=CALL_NOT_FOUND")
            println("VIDMIXI_DIAG HTML_HAS_BEPLAYER=${html.contains("bePlayer(", true)}")
            return
        }

        val (hash, encryptedSet) = player
        println(
            "VIDMIXI_DIAG BEPLAYER_OK " +
                "hashLen=${hash.length} hashPrefix=${hash.take(8)} " +
                "setChars=${encryptedSet.length}"
        )

        val plaintext = runCatching {
            decryptCryptoJs(hash, encryptedSet)
        }.getOrElse {
            println(
                "VIDMIXI_DIAG FAIL stage=DECRYPT " +
                    "exception=${it::class.simpleName} message=${it.message}"
            )
            return
        }

        println(
            "VIDMIXI_DIAG DECRYPT_OK chars=${plaintext.length} " +
                "prefix=${plaintext.take(120).replace("\n", " ")}"
        )

        val params = runCatching { JSONObject(plaintext) }.getOrElse {
            println(
                "VIDMIXI_DIAG FAIL stage=JSON " +
                    "exception=${it::class.simpleName} message=${it.message}"
            )
            return
        }

        val videoLocation = params.optString("video_location").trim()
        if (videoLocation.isBlank()) {
            println(
                "VIDMIXI_DIAG FAIL stage=JSON reason=VIDEO_LOCATION_EMPTY " +
                    "keys=${params.keys().asSequence().toList()}"
            )
            return
        }

        val hlsUrl = if (
            videoLocation.startsWith("http://") ||
            videoLocation.startsWith("https://")
        ) {
            videoLocation
        } else {
            java.net.URI(url).resolve(videoLocation).toString()
        }

        println(
            "VIDMIXI_DIAG VIDEO_LOCATION_OK " +
                "host=${runCatching { java.net.URI(hlsUrl).host }.getOrNull()} " +
                "path=${runCatching { java.net.URI(hlsUrl).path }.getOrNull()}"
        )

        val playbackHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

        val playlist = runCatching {
            app.get(
                hlsUrl,
                headers = playbackHeaders,
                referer = "$mainUrl/"
            )
        }.getOrElse {
            println(
                "VIDMIXI_DIAG FAIL stage=HLS_GET " +
                    "exception=${it::class.simpleName} message=${it.message}"
            )
            return
        }

        val isHls = playlist.text.trimStart().startsWith("#EXTM3U")
        println(
            "VIDMIXI_DIAG HLS_RESULT status=${playlist.code} " +
                "contentType=${playlist.headers["Content-Type"]} " +
                "chars=${playlist.text.length} isHls=$isHls"
        )

        if (!isHls) {
            println(
                "VIDMIXI_DIAG FAIL stage=HLS_VALIDATE reason=NOT_EXTM3U " +
                    "body=${playlist.text.take(160).replace("\n", " ")}"
            )
            return
        }

        println("VIDMIXI_DIAG EMIT type=M3U8")

        callback(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = playbackHeaders
            }
        )
    }
}
