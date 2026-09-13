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

            fun nativeTap(view: WebView, x: Float, y: Float, reason: String) {
                val safeX = x.coerceIn(1f, (view.width - 1).coerceAtLeast(1).toFloat())
                val safeY = y.coerceIn(1f, (view.height - 1).coerceAtLeast(1).toFloat())
                val now = android.os.SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, safeX, safeY, 0)
                val up = MotionEvent.obtain(now, now + 90, MotionEvent.ACTION_UP, safeX, safeY, 0)
                runCatching {
                    view.dispatchTouchEvent(down)
                    view.dispatchTouchEvent(up)
                }
                down.recycle()
                up.recycle()
                Log.i(TAG, "RUNTIME DOM_TAP x=$safeX y=$safeY reason=$reason")
            }

            fun findPlayerAndTap(view: WebView, attempt: Int) {
                if (finished.get()) return

                // Read-only DOM inspection. No element.click(), no player API call.
                // V49 Resolver Lab proved that the Pichive player surface is a large,
                // centered element (observed as alertCenter / #Player / video).
                val script = """
                    (function() {
                      try {
                        var vw = Math.max(document.documentElement.clientWidth || 0, window.innerWidth || 0);
                        var vh = Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0);
                        var cx = vw / 2, cy = vh / 2;
                        var nodes = Array.from(document.querySelectorAll(
                          '#Player,.alertCenter,video,[class*="play" i],[id*="play" i],button,[role="button"],div'
                        ));
                        var best = null;

                        nodes.forEach(function(e) {
                          var cs = getComputedStyle(e);
                          var r = e.getBoundingClientRect();
                          if (!r || r.width < 24 || r.height < 24) return;
                          if (r.bottom <= 0 || r.right <= 0 || r.left >= vw || r.top >= vh) return;
                          if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity || 1) <= 0.02) return;
                          if (cs.pointerEvents === 'none') return;

                          var tag = (e.tagName || '').toLowerCase();
                          var id = e.id || '';
                          var cls = typeof e.className === 'string' ? e.className : (e.getAttribute('class') || '');
                          var aria = e.getAttribute('aria-label') || '';
                          var title = e.getAttribute('title') || '';
                          var role = e.getAttribute('role') || '';
                          var txt = (e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120);
                          var blob = (id + ' ' + cls + ' ' + aria + ' ' + title + ' ' + role + ' ' + txt).toLowerCase();

                          var x = r.left + r.width / 2;
                          var y = r.top + r.height / 2;
                          var area = r.width * r.height;
                          var centered = Math.hypot(x - cx, y - cy) <= Math.max(vw, vh) * 0.32;
                          var score = 0;

                          if (cls.toLowerCase().indexOf('alertcenter') >= 0) score += 1000;
                          if (id.toLowerCase() === 'player') score += 850;
                          if (tag === 'video') score += 800;
                          if (/jw-display|jw-icon-display|vjs-big-play|plyr__control--overlaid|play-button|play_btn|playbtn/.test(blob)) score += 900;
                          if (/\bplay\b|oynat|izle|watch/.test(blob)) score += 500;
                          if (/player|video|poster|overlay|display|center/.test(blob)) score += 220;
                          if (centered) score += 180;
                          if (area >= 5000) score += 80;
                          if (area >= 20000) score += 80;
                          if (/pause|volume|mute|setting|fullscreen|quality|caption|subtitle/.test(blob)) score -= 900;

                          var item = {
                            score: score, x: x, y: y,
                            left: r.left, top: r.top, width: r.width, height: r.height,
                            tag: tag, id: id, cls: String(cls).slice(0, 180),
                            aria: String(aria).slice(0, 100), title: String(title).slice(0, 100)
                          };
                          if (!best || item.score > best.score) best = item;
                        });

                        return best ? JSON.stringify(best) : '';
                      } catch (e) {
                        return JSON.stringify({error:String(e)});
                      }
                    })();
                """.trimIndent()

                view.evaluateJavascript(script) { raw ->
                    if (finished.get()) return@evaluateJavascript
                    val decoded = runCatching {
                        if (raw == null || raw == "null" || raw == "\"\"") "" else
                            org.json.JSONTokener(raw).nextValue() as? String ?: ""
                    }.getOrDefault("")

                    if (decoded.isBlank()) {
                        Log.w(TAG, "RUNTIME DOM_NO_CANDIDATE attempt=$attempt")
                        return@evaluateJavascript
                    }

                    runCatching {
                        val obj = org.json.JSONObject(decoded)
                        if (obj.has("error")) {
                            Log.w(TAG, "RUNTIME DOM_ERROR ${obj.optString("error")}")
                            return@runCatching
                        }

                        val score = obj.optInt("score", 0)
                        val xCss = obj.optDouble("x", Double.NaN)
                        val yCss = obj.optDouble("y", Double.NaN)
                        Log.i(
                            TAG,
                            "RUNTIME DOM_CANDIDATE attempt=$attempt score=$score " +
                                "tag=${obj.optString("tag")} id=${obj.optString("id")} " +
                                "class=${obj.optString("cls")} cssX=$xCss cssY=$yCss " +
                                "w=${obj.optDouble("width")} h=${obj.optDouble("height")}"
                        )

                        if (score < 180 || xCss.isNaN() || yCss.isNaN()) return@runCatching

                        // getBoundingClientRect uses CSS px. WebView touch coordinates use view px.
                        // window.innerWidth -> view.width gives a robust scale even when density/zoom differs.
                        view.evaluateJavascript(
                            "JSON.stringify({w:Math.max(document.documentElement.clientWidth||0,window.innerWidth||0),h:Math.max(document.documentElement.clientHeight||0,window.innerHeight||0)})"
                        ) { vpRaw ->
                            val vpDecoded = runCatching {
                                org.json.JSONTokener(vpRaw).nextValue() as? String ?: ""
                            }.getOrDefault("")
                            val vp = runCatching { org.json.JSONObject(vpDecoded) }.getOrNull()
                            val cssW = vp?.optDouble("w", 0.0) ?: 0.0
                            val cssH = vp?.optDouble("h", 0.0) ?: 0.0

                            val touchX = if (cssW > 0.0 && view.width > 0)
                                (xCss * view.width.toDouble() / cssW).toFloat()
                            else xCss.toFloat()

                            val touchY = if (cssH > 0.0 && view.height > 0)
                                (yCss * view.height.toDouble() / cssH).toFloat()
                            else yCss.toFloat()

                            nativeTap(
                                view,
                                touchX,
                                touchY,
                                "attempt=$attempt score=$score tag=${obj.optString("tag")} id=${obj.optString("id")} class=${obj.optString("cls")}"
                            )
                        }
                    }.onFailure {
                        Log.e(TAG, "RUNTIME DOM_PARSE_FAIL ${it::class.simpleName}: ${it.message}")
                    }
                }
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

                            // V3 / V49 logic: inspect the live Pichive DOM, choose the real
                            // player/play surface, then send one native Android touch to its
                            // calculated coordinates. Retry the inspection only if HLS has not
                            // appeared yet; each attempt re-reads the current DOM.
                            main.postDelayed({ if (!finished.get()) findPlayerAndTap(v, 1) }, 650L)
                            main.postDelayed({ if (!finished.get()) findPlayerAndTap(v, 2) }, 1_600L)
                            main.postDelayed({ if (!finished.get()) findPlayerAndTap(v, 3) }, 3_000L)
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
