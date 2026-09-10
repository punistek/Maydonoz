package turkspor.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ChannelRules {
    const val URL="https://raw.githubusercontent.com/punistek/pars-plugins/main/channel-rules.json"
    private val lock=Mutex();private var checked=0L
    @Volatile private var hidden=setOf<String>()
    @Volatile private var extras=mapOf<String,String>()
    suspend fun refresh()=lock.withLock {
        if(System.currentTimeMillis()-checked<300_000) return@withLock
        try {
            val response=app.get(URL,timeout=5)
            if(response.code==200 && response.text.length<200_000) {
                val root=ObjectMapper().readTree(response.text)
                hidden=root.path("hiddenChannels").map { it.asText().trim() }.toSet()
                extras=root.path("extraChannels").fields().asSequence().associate { (k,v)->k to v.asText() }
            }
        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
        checked=System.currentTimeMillis()
    }
    fun visible(title: String)=title !in hidden
    fun extra(raw: String): String?=extras[raw.trim()]?.takeIf { it.isNotBlank() }
}
