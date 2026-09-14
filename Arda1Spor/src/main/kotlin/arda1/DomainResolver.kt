package arda1

import android.content.SharedPreferences
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Arda1 kendi domain çözümleyicisini taşır.
 * turkspor-core, domains.json veya başka spor modülüne bağlı değildir.
 *
 * Mantık:
 *  1) Son çalışan domain
 *  2) Önceki çalışmada sayfadan öğrenilen "sonraki adres"
 *  3) Bootstrap
 *
 * Çalışan sayfanın içindeki ArdaSpor .top linkleri tekrar okunur ve
 * "sonraki adres" bir sonraki açılış için saklanır.
 */
class DomainResolver(private val prefs: SharedPreferences) {
    companion object {
        const val BOOTSTRAP = "https://www.ardaspor30.top/"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
    }

    private val mutex = Mutex()

    @Volatile
    private var cached: DomainSnapshot? = null

    val currentUrl: String
        get() = cached?.url
            ?: prefs.getString("lastGood", BOOTSTRAP)
            ?: BOOTSTRAP

    suspend fun resolve(force: Boolean = false): DomainSnapshot = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { !force && now - it.checkedAt < 60_000L }?.let { return@withLock it }

        val candidates = linkedSetOf<String>()
        normalizeSite(prefs.getString("lastGood", null))?.let(candidates::add)
        normalizeSite(prefs.getString("nextDomain", null))?.let(candidates::add)
        candidates.add(BOOTSTRAP)

        val errors = mutableListOf<String>()

        for (candidate in candidates) {
            try {
                val response = app.get(
                    candidate,
                    headers = mapOf("User-Agent" to UA),
                    timeout = 12,
                )
                if (response.code != 200) continue

                val finalUrl = normalizeSite(response.url) ?: normalizeSite(candidate) ?: continue
                val doc = Jsoup.parse(response.text, finalUrl)
                val title = doc.title()
                if (!title.contains("ArdaSpor", ignoreCase = true)) continue

                val discovered = doc.select("a[href]")
                    .mapNotNull { normalizeSite(it.absUrl("href")) }
                    .filter { it != finalUrl }
                    .distinct()

                discovered.firstOrNull()?.let { next ->
                    prefs.edit().putString("nextDomain", next).apply()
                }

                val snapshot = DomainSnapshot(
                    url = finalUrl,
                    html = response.text,
                    checkedAt = System.currentTimeMillis(),
                )
                cached = snapshot
                prefs.edit()
                    .putString("lastGood", finalUrl)
                    .putLong("checkedAt", snapshot.checkedAt)
                    .apply()

                return@withLock snapshot
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${candidate}:${e.javaClass.simpleName}"
            }
        }

        throw ErrorLoadingException(
            "ArdaSpor domain bulunamadı. Son çalışan=${prefs.getString("lastGood", BOOTSTRAP)} ${errors.lastOrNull().orEmpty()}"
        )
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

data class DomainSnapshot(
    val url: String,
    val html: String,
    val checkedAt: Long,
)
