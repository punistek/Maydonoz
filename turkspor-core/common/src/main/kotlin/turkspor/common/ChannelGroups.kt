package turkspor.common

import java.text.Normalizer
import java.util.Locale

object ChannelGroups {
    const val NOTICE="Bazı yayınlar yalnızca maç saatinde açılır. Erişim sorunu varsa WARP ile deneyin."
    private fun key(s: String)=Normalizer.normalize(s.lowercase(Locale.ROOT).replace('ı','i'),Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"),"")
    val order=listOf("beIN Sports","S Sport","S Plus","Tivibu","tabii","Exxen","Spor Smart","Ulusal","Yabancı Spor","Diğer Spor")
    fun category(title: String): String {
        val n=key(title)
        return when {
            title.contains('[')->"Yabancı Spor"
            n.startsWith("bein")->"beIN Sports"
            n.startsWith("splus") || n.startsWith("ssportplus") || title.startsWith("S+")->"S Plus"
            n.startsWith("ssport")->"S Sport"
            n.startsWith("tivibu")->"Tivibu"
            n.startsWith("tabii")->"tabii"
            n.startsWith("exxen")->"Exxen"
            n.startsWith("sporsmart") || n.startsWith("smartspor")->"Spor Smart"
            Regex("^(trt|aspor|htspor|tv8|atv|a2|tv100|ekol|sports?tv|fb|gs)").containsMatchIn(n)->"Ulusal"
            else->"Diğer Spor"
        }
    }
    fun <T> sections(rows: List<T>,title: (T)->String): List<Pair<String,List<T>>> = rows
        .filter { ChannelRules.visible(title(it)) }.groupBy { category(title(it)) }.toList().sortedBy { order.indexOf(it.first) }
}
