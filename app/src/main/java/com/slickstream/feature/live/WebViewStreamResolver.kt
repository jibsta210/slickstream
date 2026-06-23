package com.slickstream.feature.live

import android.annotation.SuppressLint
import android.content.Context
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
 * Resolves a streamed.pk-style obfuscated embed PAGE into the real HLS `.m3u8` URL.
 *
 * As of 2026 these embeds mint a rotating token in client-side JS, so there's no plain-HTTP
 * formula — the only reliable path is to load the embed in an off-screen [WebView] and intercept
 * the first `.m3u8` request it fires. The base URL trick (`loadDataWithBaseURL(referer, …)`) makes
 * the WebView's origin the referrer the embed requires, so its requests aren't rejected.
 *
 * Entirely invisible — no view is ever attached; the user only ever sees ExoPlayer.
 */
@Singleton
class WebViewStreamResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        embedUrl: String,
        referer: String,
        userAgent: String,
        timeoutMs: Long = 30_000L,
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
                webViewClient = object : WebViewClient() {
                    // Called on a background WebView thread — completing the deferred is thread-safe.
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (!captured.isCompleted && looksLikeStream(u)) captured.complete(u)
                        return null
                    }
                }
            }
            // One sandbox-free iframe pointed at the embed; base URL = referer so the embed's
            // referrer/origin check passes and it actually loads its player + token.
            val html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width"></head>
                <body style="margin:0;background:#000">
                <iframe src="$embedUrl" style="position:fixed;inset:0;width:100%;height:100%;border:0"
                  allow="autoplay; encrypted-media; fullscreen; picture-in-picture"></iframe>
                </body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL(referer, html, "text/html", "UTF-8", null)

            withTimeoutOrNull(timeoutMs) { captured.await() }
        } catch (e: Exception) {
            null
        } finally {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }
    }

    /** First playlist request wins — the master .m3u8 (or the known token CDN hosts). */
    private fun looksLikeStream(u: String): Boolean =
        u.contains(".m3u8", ignoreCase = true) ||
            u.contains("strmd.st", ignoreCase = true) ||
            u.contains("vipstreams.in", ignoreCase = true)
}
