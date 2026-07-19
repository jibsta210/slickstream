package com.slickstream.feature.live

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a streamed.pk / embed.st obfuscated embed PAGE into the real HLS `.m3u8` URL by loading
 * it in an off-screen [WebView] and capturing the first playlist request it fires.
 *
 * These embeds (now on embed.st) mint a rotating token in client-side JS and fetch the manifest via
 * a Swarmcloud-P2P HLS engine — frequently over `fetch`/XHR that `shouldInterceptRequest` doesn't
 * surface. So we capture from BOTH: the network interceptor AND a JS hook that overrides
 * `window.fetch` + `XMLHttpRequest.open`. Entirely invisible; the user only ever sees ExoPlayer.
 */
@Singleton
class WebViewStreamResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    suspend fun resolve(
        embedUrl: String,
        pageReferer: String,
        userAgent: String,
        timeoutMs: Long = 40_000L,
    ): String? = withContext(Dispatchers.Main) {
        val captured = CompletableDeferred<String>()
        var webView: WebView? = null
        try {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                }
                // JS-side capture: hook fetch + XHR for manifest URLs the interceptor can miss.
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onUrl(u: String?) {
                        if (u != null && !captured.isCompleted && looksLikeStream(u)) captured.complete(u)
                    }
                }, "SlickCapture")

                webViewClient = object : WebViewClient() {
                    // Network-side capture (covers most direct .m3u8 requests).
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (!captured.isCompleted && looksLikeStream(u)) captured.complete(u)
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Override fetch + XHR to report every requested URL back to native, and
                        // strip sandbox off any nested iframes so the embed's player can run.
                        view?.evaluateJavascript(HOOK_JS, null)
                    }

                    // CRITICAL crash survival: a heavy/hostile embed page (e.g. a big live event) can
                    // crash the WebView RENDERER process. If this returns false (the default), Android
                    // tears down the WHOLE APP — the "was reliable, now instant crash on stream select"
                    // report. Returning true keeps the app alive; we just fail this resolve (-> null ->
                    // "try another source") instead of dying. (onRenderProcessGone: API 26+.)
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?,
                    ): Boolean {
                        if (!captured.isCompleted) {
                            captured.completeExceptionally(IllegalStateException("WebView renderer gone"))
                        }
                        runCatching { view?.destroy() }
                        return true
                    }
                }
            }
            // Load the embed PAGE with a Referer of its parent site (streamed.pk), which is what the
            // embed's referrer check expects.
            webView.loadUrl(embedUrl, mapOf("Referer" to pageReferer))

            // await() can throw (renderer-gone completes exceptionally) — treat any failure as "no
            // stream found" so the caller shows "try another source" instead of the app crashing.
            withTimeoutOrNull(timeoutMs) { runCatching { captured.await() }.getOrNull() }
        } catch (e: Throwable) {
            null
        } finally {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.removeJavascriptInterface("SlickCapture")
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }
    }

    private fun looksLikeStream(u: String): Boolean {
        val s = u.lowercase()
        return s.contains(".m3u8") ||
            s.contains("/playlist") || s.contains("/master") || s.contains("/index.") ||
            s.contains("strmd.st") || s.contains("vipstreams.in")
    }

    private companion object {
        // Re-report fetch/XHR targets to native, and unsandbox nested iframes + autoplay videos.
        const val HOOK_JS = """
            (function(){
              try {
                var of = window.fetch;
                if (of) window.fetch = function(){ try { var a = arguments[0];
                  var u = (typeof a === 'string') ? a : (a && a.url); if (u) SlickCapture.onUrl(u); } catch(e){}
                  return of.apply(this, arguments); };
                var ox = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(m,u){ try { if (u) SlickCapture.onUrl(u); } catch(e){}
                  return ox.apply(this, arguments); };
                document.querySelectorAll('iframe').forEach(function(f){ f.removeAttribute('sandbox'); });
                document.querySelectorAll('video').forEach(function(v){ try { v.play(); } catch(e){} });
              } catch(e){}
            })();
        """
    }
}
