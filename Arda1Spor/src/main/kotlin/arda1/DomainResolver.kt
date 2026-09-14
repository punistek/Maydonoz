package arda1

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** Arda1'e özel, turkspor-core'dan tamamen bağımsız domain resolver. */
class DomainResolver(private val prefs: SharedPreferences) {
    companion object {
        const val BOOTSTRAP = "https://www.ardaspor30.top/"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    }

    private val mutex = Mutex()

    @Volatile
    private var cachedUrl: String? = null

    suspend fun resolveOrFallback(): String? = mutex.withLock {
        cachedUrl?.let { return@withLock it }

        val candidates = linkedSetOf<String>()
        normalizeSite(prefs.getString("lastGood", null))?.let(candidates::add)
        normalizeSite(prefs.getString("nextDomain", null))?.let(candidates::add)
        candidates.add(BOOTSTRAP)

        for (candidate in candidates) {
            try {
                val response = app.get(candidate, headers = mapOf("User-Agent" to UA), timeout = 7)
                if (response.code !in 200..299) continue
                val finalUrl = normalizeSite(response.url) ?: normalizeSite(candidate) ?: continue
                val doc = Jsoup.parse(response.text, finalUrl)
                if (!doc.title().contains("ArdaSpor", ignoreCase = true)) continue

                // Sayfada başka ardaspornn.top linki varsa sonraki domain olarak sakla.
                doc.select("a[href]")
                    .mapNotNull { normalizeSite(it.absUrl("href")) }
                    .firstOrNull { it != finalUrl }
                    ?.let { prefs.edit().putString("nextDomain", it).apply() }

                cachedUrl = finalUrl
                prefs.edit().putString("lastGood", finalUrl).apply()
                System.out.println("[ARDA1_DOMAIN] OK $finalUrl")
                return@withLock finalUrl
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.out.println("[ARDA1_DOMAIN] FAIL $candidate ${e.javaClass.simpleName}:${e.message.orEmpty()}")
            }
        }

        // BTK sertifikası / SSL engeli varken ana sayfayı boş bırakmıyoruz.
        val fallback = normalizeSite(prefs.getString("lastGood", null)) ?: BOOTSTRAP
        cachedUrl = fallback
        System.out.println("[ARDA1_DOMAIN] FALLBACK $fallback")
        fallback
    }

    private fun normalizeSite(value: String?): String? = runCatching {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        val uri = URI(raw)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme != "https") return null
        if (uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
        if (!Regex("(www\\.)?ardaspor[0-9]+\\.top").matches(host)) return null
        "https://$host/"
    }.getOrNull()
}
