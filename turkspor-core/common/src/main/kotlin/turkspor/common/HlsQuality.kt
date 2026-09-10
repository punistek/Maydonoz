package turkspor.common

import com.lagradost.cloudstream3.utils.*
import java.net.URI

data class HlsVariant(val url: String,val height: Int,val bandwidth: Long=0)
object HlsQuality {
    fun hint(label: String): Int {
        Regex("(?i)([0-9]{3,4})\\s*[x×]\\s*([0-9]{3,4})").find(label)?.let { return minOf(it.groupValues[1].toInt(),it.groupValues[2].toInt()) }
        return when {
            Regex("(?i)\\b(4k|uhd|2160p?)\\b").containsMatchIn(label)->2160
            Regex("(?i)\\b(1920|1080p?|fhd|full\\s*hd)\\b").containsMatchIn(label)->1080
            Regex("(?i)\\b(1280|720p?|hd)\\b").containsMatchIn(label)->720
            Regex("(?i)\\b(576p?|sd)\\b").containsMatchIn(label)->576
            Regex("(?i)\\b480p?\\b").containsMatchIn(label)->480
            else->0
        }
    }
    fun variants(text: String,url: String,label: String=""): List<HlsVariant> {
        var height=0;var bandwidth=0L;var pending=false;val rows=mutableListOf<HlsVariant>()
        for(raw in text.lineSequence()) {
            val line=raw.trim()
            if(line.startsWith("#EXT-X-STREAM-INF:")) {
                height=Regex("RESOLUTION=([0-9]+)x([0-9]+)",RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                bandwidth=Regex("(?:^|,)BANDWIDTH=([0-9]+)").find(line.substringAfter(':'))?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                pending=true
            } else if(pending && line.isNotBlank() && !line.startsWith('#')) {
                runCatching { URI(url).resolve(line) }.getOrNull()?.takeIf { it.scheme in listOf("http","https") }?.let { rows.add(HlsVariant(it.toString(),height,bandwidth)) }
                pending=false
            }
        }
        // Keep the master when a separate audio rendition is required.
        if(rows.isEmpty() || text.lineSequence().any { it.trimStart().startsWith("#EXT-X-MEDIA:",true) && it.contains("TYPE=AUDIO",true) && it.contains("URI=",true) })
            return listOf(HlsVariant(url,rows.maxOfOrNull { it.height } ?: hint(label)))
        return rows.distinctBy { it.url }.sortedWith(compareByDescending<HlsVariant> { it.height }.thenByDescending { it.bandwidth })
    }
    suspend fun links(source: String,label: String,url: String,text: String,referer: String,headers: Map<String,String>): List<ExtractorLink> =
        variants(text,url,label).map { v -> newExtractorLink(source,label+if(v.height>0) " • ${v.height}p" else "",v.url,ExtractorLinkType.M3U8) {
            this.referer=referer;this.headers=headers;quality=if(v.height>0) v.height else Qualities.Unknown.value
        } }
    fun sorted(links: List<ExtractorLink>)=links.distinctBy { it.url to it.headers }.sortedByDescending { it.quality }
}
