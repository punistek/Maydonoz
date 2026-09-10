package turkspor

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class SiteSnapshot(val url: String, val channels: List<SportsChannel>, val checkedAt: Long)

class DomainResolver(private val preferences: SharedPreferences) {
    companion object {
        const val GATEWAY = "https://www.selcuksportshd.is/"
        const val BOOTSTRAP = "https://www.selcuksportshdbd813bd00f.xyz/"
        const val MANIFEST = "https://raw.githubusercontent.com/punistek/pars-plugins/main/domains.json"
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
    private val mutex = Mutex()
    @Volatile private var cached: SiteSnapshot? = null
    val currentUrl: String get() = cached?.url ?: preferences.getString("lastGood", BOOTSTRAP) ?: BOOTSTRAP
    val checkedAt: Long get() = cached?.checkedAt ?: preferences.getLong("checkedAt", 0)

    suspend fun resolve(force: Boolean = false): SiteSnapshot = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { !force && now - it.checkedAt < 60_000 }?.let { return@withLock it }
        val candidates = linkedSetOf<String>()
        val manual = preferences.getString("manual", "").orEmpty()
        SportsParser.siteUrl(manual)?.let(candidates::add)
        // Normal loads are fast. A forced refresh checks the maintained manifest and gateway first.
        if (!force) SportsParser.siteUrl(currentUrl)?.let(candidates::add)
        val errors = mutableListOf<String>()
        suspend fun verify(url: String): SiteSnapshot? {
            return try {
                val response = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 12)
                val finalUrl = SportsParser.siteUrl(response.url) ?: return null
                if (response.code != 200) return null
                val items = SportsParser.channels(response.text, finalUrl)
                if (items.isEmpty()) return null
                SiteSnapshot(finalUrl, items, System.currentTimeMillis()).also {
                    cached = it
                    preferences.edit().putString("lastGood", it.url).putLong("checkedAt", it.checkedAt).apply()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add(e.javaClass.simpleName); null }
        }
        if (!force) {
            candidates.toList().forEach { verify(it)?.let { result -> return@withLock result } }
        }
        try {
            val response = app.get(MANIFEST, timeout = 8)
            if (response.code == 200) {
                val root = JSONObject(response.text)
                if (root.optInt("schemaVersion") == 1) {
                    val urls = root.optJSONObject("sources")?.optJSONObject("selcuksports")?.optJSONArray("candidates")
                    if (urls != null) for (i in 0 until minOf(urls.length(), 5))
                        SportsParser.siteUrl(urls.optString(i))?.let(candidates::add)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try {
            val response = app.get(GATEWAY, headers = mapOf("User-Agent" to UA), timeout = 8)
            if (response.code == 200) candidates.addAll(SportsParser.gatewayTargets(response.text, GATEWAY).take(5))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        SportsParser.siteUrl(currentUrl)?.let(candidates::add)
        candidates.add(BOOTSTRAP)
        for (candidate in candidates.take(8)) verify(candidate)?.let { return@withLock it }
        // Never overwrite the last known domain with an ad/parking page or failed candidate.
        throw ErrorLoadingException("SelçukSports kaynağına ulaşılamadı. Eklenti ayarlarından Domaini yenile'yi deneyin. ${errors.lastOrNull().orEmpty()}")
    }

    suspend fun setManual(value: String): SiteSnapshot {
        if (value.isBlank()) {
            preferences.edit().remove("manual").apply()
            return resolve(true)
        }
        val url = SportsParser.siteUrl(value) ?: throw ErrorLoadingException("Geçerli bir HTTPS SelçukSports adresi girin.")
        val old = preferences.getString("manual", "").orEmpty()
        preferences.edit().putString("manual", url).apply()
        try {
            val result = resolve(true)
            if (result.url != url) throw ErrorLoadingException("Girilen adres doğrulanamadı; çalışan adres korundu.")
            return result
        } catch (e: Exception) {
            preferences.edit().putString("manual", old).apply()
            throw e
        }
    }
}
