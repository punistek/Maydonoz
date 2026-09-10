package turkspor.arda

import android.content.Context
import android.graphics.*
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Native UI tiles keep wide wordmarks readable in Cloudstream's cropped poster grid. */
class ChannelArtwork(context: Context) {
    private val directory = File(context.cacheDir, "turkspor_arda_cards_v1").apply { mkdirs() }
    private val semaphore = Semaphore(4)
    private fun file(channel: SportsChannel) = File(directory, "${channel.id}.png")
    fun poster(channel: SportsChannel): String = Uri.fromFile(file(channel)).toString()

    suspend fun prepare(channels: List<SportsChannel>) = withContext(Dispatchers.IO) {
        coroutineScope {
            channels.distinctBy { it.id }.map { channel -> async { semaphore.withPermit { render(channel) } } }.awaitAll()
        }
    }
    private fun render(channel: SportsChannel) {
        val output = file(channel)
        if (output.isFile && System.currentTimeMillis() - output.lastModified() < 24 * 60 * 60 * 1000) return
        val brand = ChannelBranding.forChannel(channel)
        val logo = runCatching {
            val connection = URL(brand.logo).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            try { connection.inputStream.use { BitmapFactory.decodeStream(it) } }
            finally { connection.disconnect() }
        }.getOrNull()
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(16, 22, 32))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.rgb(248, 250, 252)
        canvas.drawRoundRect(RectF(56f, 56f, 584f, 248f), 22f, 22f, paint)
        if (logo != null) {
            val scale = minOf(464f / logo.width, 144f / logo.height)
            val width = logo.width * scale
            val height = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(320f - width / 2, 152f - height / 2, 320f + width / 2, 152f + height / 2), paint)
            logo.recycle()
        } else {
            paint.color = Color.rgb(30, 40, 60)
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 38f
            while (paint.measureText(brand.title) > 464 && paint.textSize > 18) paint.textSize -= 1
            canvas.drawText(brand.title, 320f, 166f, paint)
        }
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 32f
        while (paint.measureText(brand.title) > 520 && paint.textSize > 18) paint.textSize -= 1
        canvas.drawText(brand.title, 320f, 310f, paint)
        val temp = File(directory, "${channel.id}.${Thread.currentThread().id}.tmp")
        try {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(output)) temp.copyTo(output, overwrite = true)
        } finally { temp.delete(); bitmap.recycle() }
    }
}
