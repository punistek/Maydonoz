package com.pars.roketdizi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class RoketRuntimeResult(
    val mediaUrl: String,
    val iframeUrl: String,
    val userAgent: String,
    val cookie: String
)

object RoketRuntimeContext {
    @Volatile
    var context: Context? = null
}

object RoketWebRuntime {
    private const val TAG = "ROKET"
    private const val TIMEOUT_MS = 25_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(context: Context, detailUrl: String): RoketRuntimeResult? =
        suspendCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            var webView: WebView? = null
            var iframeUrl: String? = null
            var userAgent = ""

            fun finish(result: RoketRuntimeResult?) {
                if (!finished.compareAndSet(false, true)) return
                main.removeCallbacksAndMessages(null)
                val view = webView
                webView = null
                runCatching {
                    view?.stopLoading()
                    view?.loadUrl("about:blank")
                    view?.clearHistory()
                    view?.removeAllViews()
                    view?.destroy()
                }
                continuation.resume(result)
            }

            fun isPichiveIframe(url: String): Boolean =
                url.contains("pichive.online/iframe.php", ignoreCase = true)

            fun isHls(url: String): Boolean {
                val lower = url.lowercase()
                return lower.contains(".m3u8") ||
                    lower.contains("/master.m3u8") ||
                    lower.contains("application/vnd.apple.mpegurl")
            }

            fun nativeTapCenter(view: WebView) {
                val x = if (view.width > 0) view.width / 2f else 360f
                val y = if (view.height > 0) view.height / 2f else 320f
                val now = android.os.SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                runCatching {
                    view.dispatchTouchEvent(down)
                    view.dispatchTouchEvent(up)
                }
                down.recycle()
                up.recycle()
                Log.i(TAG, "RUNTIME TAP x=$x y=$y")
            }

            main.post {
                try {
                    val view = WebView(context)
                    webView = view
                    userAgent = view.settings.userAgentString.orEmpty()

                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.databaseEnabled = true
                    view.settings.mediaPlaybackRequiresUserGesture = false
                    view.settings.loadsImagesAutomatically = true

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            v: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            if (url.isBlank()) return null

                            if (isPichiveIframe(url) && iframeUrl == null) {
                                iframeUrl = url
                                Log.i(TAG, "RUNTIME IFRAME $url")

                                // Open the real player as the top page. This avoids trying to
                                // reach a cross-origin iframe DOM from the RoketDizi parent.
                                main.postDelayed({
                                    if (!finished.get()) {
                                        runCatching {
                                            v?.loadUrl(
                                                url,
                                                mapOf(
                                                    "Referer" to detailUrl,
                                                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                                                )
                                            )
                                        }
                                    }
                                }, 250L)
                            }

                            if (isHls(url)) {
                                val frame = iframeUrl ?: return null
                                val cookie = CookieManager.getInstance().getCookie(frame).orEmpty()
                                Log.i(TAG, "RUNTIME HLS $url")
                                main.post {
                                    finish(
                                        RoketRuntimeResult(
                                            mediaUrl = url,
                                            iframeUrl = frame,
                                            userAgent = userAgent,
                                            cookie = cookie
                                        )
                                    )
                                }
                            }
                            return null
                        }

                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            Log.i(TAG, "RUNTIME PAGE_START ${url.orEmpty()}")
                        }

                        override fun onPageFinished(v: WebView?, url: String?) {
                            val current = url.orEmpty()
                            Log.i(TAG, "RUNTIME PAGE_FINISH $current")
                            if (v == null || !isPichiveIframe(current)) return

                            // No JS hook / no player API call: only a native center tap, repeated
                            // a few times because the visible play control may mount slightly late.
                            main.postDelayed({ if (!finished.get()) nativeTapCenter(v) }, 700L)
                            main.postDelayed({ if (!finished.get()) nativeTapCenter(v) }, 1_700L)
                            main.postDelayed({ if (!finished.get()) nativeTapCenter(v) }, 3_000L)
                        }
                    }

                    Log.i(TAG, "RUNTIME DETAIL_LOAD $detailUrl")
                    view.loadUrl(
                        detailUrl,
                        mapOf(
                            "Referer" to "https://roketdizi.life/",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                        )
                    )

                    main.postDelayed({
                        if (!finished.get()) {
                            Log.w(TAG, "RUNTIME TIMEOUT iframe=${iframeUrl.orEmpty()}")
                            finish(null)
                        }
                    }, TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.e(TAG, "RUNTIME INIT_FAIL ${t::class.simpleName}: ${t.message}")
                    finish(null)
                }
            }
        }
}
