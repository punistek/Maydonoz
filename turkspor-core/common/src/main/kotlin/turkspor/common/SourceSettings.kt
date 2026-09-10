package turkspor.common

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

object SourceSettings {
    fun show(context: Context,title: String,current: ()->String,checked: ()->Long,update: suspend (String?)->Int) {
        fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
        fun color(name: String,fallback: Int): Int {
            val id=context.resources.getIdentifier(name,"attr",context.packageName)
            val value=TypedValue()
            return if(id!=0 && context.theme.resolveAttribute(id,value,true)) {
                if(value.resourceId!=0) runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback) else value.data
            } else fallback
        }
        val surface=color("colorSurface",Color.rgb(24,24,28));val foreground=color("colorOnSurface",Color.WHITE)
        val muted=color("colorOnSurfaceVariant",Color.LTGRAY);val accent=color("colorPrimary",Color.rgb(165,192,255))
        val dialog=BottomSheetDialog(context)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val scroll=ScrollView(context)
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(16),dp(24),dp(24));background=GradientDrawable().apply { setColor(surface);cornerRadii=floatArrayOf(dp(28).toFloat(),dp(28).toFloat(),dp(28).toFloat(),dp(28).toFloat(),0f,0f,0f,0f) } }
        fun text(value: String,size: Float=14f,bold: Boolean=false)=TextView(context).apply {
            text=value;textSize=size;setTextColor(if(bold)foreground else muted);if(bold)typeface=Typeface.DEFAULT_BOLD
            setPadding(0,dp(8),0,dp(8));root.addView(this)
        }
        val grip=View(context).apply { background=GradientDrawable().apply { setColor(muted);cornerRadius=dp(3).toFloat() } }
        root.addView(grip,LinearLayout.LayoutParams(dp(36),dp(4)).apply { gravity=android.view.Gravity.CENTER_HORIZONTAL;bottomMargin=dp(12) })
        text(title,22f,true);text("Kaynak ayarları",13f)
        val address=text("",15f,true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.MIDDLE;setTextIsSelectable(true) }
        val status=text("")
        val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true;visibility=View.GONE;indeterminateTintList=ColorStateList.valueOf(accent) }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(3)))
        fun button(label: String)=MaterialButton(context).apply {
            text=label;isAllCaps=false;cornerRadius=dp(20);minHeight=dp(52)
            root.addView(this,LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=dp(10) })
        }
        val refresh=button("Adresi ve kanalları yenile")
        val manualToggle=button("Manuel adres")
        val field=TextInputLayout(context).apply { hint="HTTPS site veya liste adresi";boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE;visibility=View.GONE }
        val input=TextInputEditText(field.context).apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine(true) }
        field.addView(input);root.addView(field,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        val save=button("Adresi kaydet").apply { visibility=View.GONE }
        val automatic=button("Otomatik adresi kullan").apply { visibility=View.GONE }
        text(ChannelGroups.NOTICE,13f)
        button("WARP'ı aç").setOnClickListener {
            val launch=context.packageManager.getLaunchIntentForPackage("com.cloudflare.onedotonedotonedotone")
                ?: Intent(Intent.ACTION_VIEW,Uri.parse("https://one.one.one.one/"))
            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        text("made by Wiojelt with love",12f).apply {
            val credit=android.text.SpannableString(text)
            credit.setSpan(object: android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://twitter.com/wiojelt")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            },8,15,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text=credit;movementMethod=android.text.method.LinkMovementMethod.getInstance();setLinkTextColor(accent)
        }
        fun render(message: String="") {
            address.text=runCatching { Uri.parse(current()).host }.getOrNull() ?: current()
            status.text=message.ifEmpty { if(checked()>0) "Son kontrol: "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(checked())) else "Henüz kontrol edilmedi" }
        }
        fun action(manual: String?) {
            refresh.isEnabled=false;save.isEnabled=false;automatic.isEnabled=false;progress.visibility=View.VISIBLE;render("Kontrol ediliyor…")
            scope.launch { try {
                val count=withContext(Dispatchers.IO) { update(manual) };render("✓ Güncel · $count kanal")
            } catch(e: CancellationException) { throw e } catch(e: Exception) { render(e.message ?: "Kaynağa ulaşılamadı") }
            finally { refresh.isEnabled=true;save.isEnabled=true;automatic.isEnabled=true;progress.visibility=View.GONE } }
        }
        manualToggle.setOnClickListener { val show=field.visibility!=View.VISIBLE;field.visibility=if(show)View.VISIBLE else View.GONE;save.visibility=field.visibility;automatic.visibility=field.visibility }
        refresh.setOnClickListener { action(null) }
        save.setOnClickListener { val value=input.text.toString().trim();if(!value.startsWith("https://"))field.error="HTTPS adresi girin" else {field.error=null;action(value)} }
        automatic.setOnClickListener { action("") }
        render();scroll.addView(root);dialog.setContentView(scroll)
        dialog.setOnDismissListener { scope.cancel() };dialog.setOnShowListener { dialog.behavior.peekHeight=(context.resources.displayMetrics.heightPixels*.9).toInt() }
        dialog.show()
    }
}
