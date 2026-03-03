package com.codex.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

object WebAutomationManager {
    private const val TAG = "WebAutomationManager"
    private const val DEFAULT_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var activeSessionId: String? = null

    private data class Session(
        val id: String,
        val webView: WebView,
        val sessionName: String?,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        @Volatile var currentUrl: String = "about:blank"
        @Volatile var pageTitle: String = ""
        @Volatile var pageLoaded: Boolean = false
    }

    fun handleCall(context: Context, method: String, params: JSONObject): JSONObject {
        return try {
            when (method) {
                "start_web" -> startWeb(context, params)
                "stop_web" -> stopWeb(params)
                "web_navigate" -> webNavigate(params)
                "web_eval" -> webEval(params)
                "web_click" -> webClick(params)
                "web_fill" -> webFill(params)
                "web_wait_for" -> webWaitFor(params)
                "web_snapshot" -> webSnapshot(params)
                "web_content" -> webContent(params)
                "web_file_upload" -> unsupported("web_file_upload", "not_supported_in_anyclaw_webview_bridge")
                else -> unsupported(method, "unsupported_method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleCall failed: $method", e)
            JSONObject()
                .put("ok", false)
                .put("method", method)
                .put("error", e.message ?: "unknown_error")
        }
    }

    private fun startWeb(context: Context, params: JSONObject): JSONObject {
        val url = params.optString("url", "about:blank").ifBlank { "about:blank" }
        val userAgent = params.optString("user_agent", "").trim().ifBlank { null }
        val sessionName = params.optString("session_name", "").trim().ifBlank { null }
        val headers = jsonObjectToMap(params.optJSONObject("headers"))

        val sessionRef = AtomicReference<Session?>()
        runOnMainSync {
            val webView = WebView(context.applicationContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = true
            if (!userAgent.isNullOrBlank()) {
                webView.settings.userAgentString = userAgent
            }

            val id = UUID.randomUUID().toString()
            val session = Session(id = id, webView = webView, sessionName = sessionName)
            session.currentUrl = url
            session.pageLoaded = false
            sessionRef.set(session)

            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(view: WebView, startedUrl: String, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        session.currentUrl = startedUrl
                        session.pageLoaded = false
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        session.currentUrl = finishedUrl
                        session.pageTitle = view.title ?: ""
                        session.pageLoaded = true
                    }
                }

            sessions[id] = session
            activeSessionId = id

            if (headers.isNotEmpty()) {
                webView.loadUrl(url, headers)
            } else {
                webView.loadUrl(url)
            }
        }

        val session = sessionRef.get()
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "failed_to_create_session")

        return JSONObject()
            .put("ok", true)
            .put("status", "started")
            .put("session_id", session.id)
            .put("url", session.currentUrl)
            .put("session_name", session.sessionName ?: "")
            .put("active_sessions", sessions.size)
    }

    private fun stopWeb(params: JSONObject): JSONObject {
        val closeAll = params.optBoolean("close_all", false)
        if (closeAll) {
            val ids = sessions.keys().toList()
            ids.forEach { id -> destroySession(id) }
            activeSessionId = null
            return JSONObject()
                .put("ok", true)
                .put("status", "stopped")
                .put("close_all", true)
                .put("active_sessions", sessions.size)
        }

        val session = resolveSession(params)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "session_not_found")

        destroySession(session.id)
        if (activeSessionId == session.id) {
            activeSessionId = sessions.keys.firstOrNull()
        }
        return JSONObject()
            .put("ok", true)
            .put("status", "stopped")
            .put("session_id", session.id)
            .put("active_sessions", sessions.size)
    }

    private fun webNavigate(params: JSONObject): JSONObject {
        val session = resolveSession(params)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "session_not_found")
        val url = params.optString("url", "").trim()
        if (url.isBlank()) {
            return JSONObject().put("ok", false).put("error", "missing_url")
        }
        val headers = jsonObjectToMap(params.optJSONObject("headers"))

        runOnMainSync {
            session.pageLoaded = false
            session.currentUrl = url
            if (headers.isNotEmpty()) {
                session.webView.loadUrl(url, headers)
            } else {
                session.webView.loadUrl(url)
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("status", "navigating")
            .put("session_id", session.id)
            .put("url", url)
    }

    private fun webEval(params: JSONObject): JSONObject {
        val session = resolveSession(params)
            ?: return JSONObject().put("ok", false).put("error", "session_not_found")
        val script = params.optString("script", "")
        if (script.isBlank()) {
            return JSONObject().put("ok", false).put("error", "missing_script")
        }
        val timeout = params.optLong("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(1000L, 60_000L)
        val raw = evaluateJavascriptSync(session.webView, script, timeout)
        val decoded = decodeJsValue(raw)
        return JSONObject()
            .put("ok", true)
            .put("status", "ok")
            .put("session_id", session.id)
            .put("result", decoded)
            .put("raw_result", raw)
    }

    private fun webClick(params: JSONObject): JSONObject {
        val session = resolveSession(params)
            ?: return JSONObject().put("ok", false).put("error", "session_not_found")
        val ref = params.optString("ref", "").trim()
        if (ref.isBlank()) {
            return JSONObject().put("ok", false).put("error", "missing_ref")
        }
        val script =
            """
            (function() {
              try {
                var target = document.querySelector('[aria-ref="${escapeForJs(ref)}"]');
                if (!target) return JSON.stringify({ ok:false, error:'ref_not_found' });
                try { target.scrollIntoView({ block: 'center', inline: 'center' }); } catch(e) {}
                target.dispatchEvent(new MouseEvent('mousedown', { bubbles:true, cancelable:true }));
                target.dispatchEvent(new MouseEvent('mouseup', { bubbles:true, cancelable:true }));
                target.dispatchEvent(new MouseEvent('click', { bubbles:true, cancelable:true }));
                return JSON.stringify({ ok:true, ref:'${escapeForJs(ref)}', tag:String(target.tagName||'').toLowerCase() });
              } catch (e) {
                return JSON.stringify({ ok:false, error:String(e) });
              }
            })();
            """.trimIndent()
        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val result = parseJsonOrFallback(raw)
        return JSONObject()
            .put("ok", result.optBoolean("ok", false))
            .put("status", if (result.optBoolean("ok", false)) "ok" else "failed")
            .put("session_id", session.id)
            .put("result", result)
    }

    private fun webFill(params: JSONObject): JSONObject {
        val session = resolveSession(params)
            ?: return JSONObject().put("ok", false).put("error", "session_not_found")
        val selector = params.optString("selector", "").trim()
        val value = params.optString("value", "")
        if (selector.isBlank()) {
            return JSONObject().put("ok", false).put("error", "missing_selector")
        }
        val script =
            """
            (function() {
              try {
                var el = document.querySelector("${escapeForJs(selector)}");
                if (!el) return JSON.stringify({ ok:false, error:'element_not_found' });
                el.focus();
                el.value = "${escapeForJs(value)}";
                el.dispatchEvent(new Event('input', { bubbles:true }));
                el.dispatchEvent(new Event('change', { bubbles:true }));
                return JSON.stringify({ ok:true, tag:String(el.tagName||'').toLowerCase() });
              } catch (e) {
                return JSON.stringify({ ok:false, error:String(e) });
              }
            })();
            """.trimIndent()
        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val result = parseJsonOrFallback(raw)
        return JSONObject()
            .put("ok", result.optBoolean("ok", false))
            .put("status", if (result.optBoolean("ok", false)) "ok" else "failed")
            .put("session_id", session.id)
            .put("result", result)
    }

    private fun webWaitFor(params: JSONObject): JSONObject {
        val session = resolveSession(params)
            ?: return JSONObject().put("ok", false).put("error", "session_not_found")
        val selector = params.optString("selector", "").trim().ifBlank { null }
        val timeoutMs = params.optLong("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(1000L, 60_000L)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val js =
                if (selector == null) {
                    "(function(){ return JSON.stringify({ ready: String(document.readyState||'') }); })();"
                } else {
                    "(function(){ return JSON.stringify({ ready: String(document.readyState||''), found: !!document.querySelector(\"${escapeForJs(selector)}\") }); })();"
                }
            val raw = evaluateJavascriptSync(session.webView, js, 3000L)
            val state = parseJsonOrFallback(raw)
            val ready = state.optString("ready", "")
            val found = state.optBoolean("found", false)
            if (selector == null) {
                if (ready == "complete" || ready == "interactive") {
                    return JSONObject()
                        .put("ok", true)
                        .put("status", "ok")
                        .put("session_id", session.id)
                        .put("ready", ready)
                }
            } else if (found) {
                return JSONObject()
                    .put("ok", true)
                    .put("status", "ok")
                    .put("session_id", session.id)
                    .put("selector", selector)
                    .put("ready", ready)
            }
            Thread.sleep(200L)
        }

        return JSONObject()
            .put("ok", false)
            .put("status", "timeout")
            .put("session_id", session.id)
            .put("selector", selector ?: JSONObject.NULL)
    }

    private fun webSnapshot(params: JSONObject): JSONObject {
        val session = resolveSession(params)
            ?: return JSONObject().put("ok", false).put("error", "session_not_found")
        val includeLinks = params.optBoolean("include_links", true)
        val includeImages = params.optBoolean("include_images", false)
        val maxChars = params.optInt("max_chars", 16_000).coerceIn(1000, 80_000)

        val script =
            """
            (function() {
              try {
                var idx = 1;
                var refs = [];
                var candidates = document.querySelectorAll('a,button,input,textarea,select,[role="button"],[onclick]');
                for (var i = 0; i < candidates.length; i++) {
                  var el = candidates[i];
                  var ref = el.getAttribute('aria-ref');
                  if (!ref) {
                    ref = 'e' + idx++;
                    el.setAttribute('aria-ref', ref);
                  }
                  if (refs.length < 200) {
                    var txt = (el.innerText || el.textContent || el.getAttribute('aria-label') || el.getAttribute('value') || '').trim();
                    refs.push({ ref: ref, tag: String(el.tagName || '').toLowerCase(), text: txt.slice(0, 120) });
                  }
                }

                var links = [];
                if (${if (includeLinks) "true" else "false"}) {
                  var as = document.querySelectorAll('a[href]');
                  for (var j = 0; j < as.length && links.length < 200; j++) {
                    var a = as[j];
                    links.push({ href: String(a.href || ''), text: String((a.innerText || a.textContent || '').trim()).slice(0, 120) });
                  }
                }

                var images = [];
                if (${if (includeImages) "true" else "false"}) {
                  var imgs = document.querySelectorAll('img[src]');
                  for (var k = 0; k < imgs.length && images.length < 120; k++) {
                    var im = imgs[k];
                    images.push({ src: String(im.src || ''), alt: String((im.alt || '').trim()).slice(0, 120) });
                  }
                }

                var text = String((document.body && (document.body.innerText || document.body.textContent)) || '').trim();
                return JSON.stringify({
                  ok: true,
                  title: String(document.title || ''),
                  url: String(location.href || ''),
                  readyState: String(document.readyState || ''),
                  text: text,
                  refs: refs,
                  links: links,
                  images: images
                });
              } catch (e) {
                return JSON.stringify({ ok:false, error:String(e) });
              }
            })();
            """.trimIndent()

        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val parsed = parseJsonOrFallback(raw)
        val text = parsed.optString("text", "")
        val clipped = if (text.length > maxChars) text.substring(0, maxChars) else text
        parsed.put("text", clipped)

        return JSONObject()
            .put("ok", parsed.optBoolean("ok", false))
            .put("status", if (parsed.optBoolean("ok", false)) "ok" else "failed")
            .put("session_id", session.id)
            .put("snapshot", parsed)
    }

    private fun webContent(params: JSONObject): JSONObject {
        val copy = JSONObject(params.toString())
        copy.put("include_links", params.optBoolean("include_links", true))
        copy.put("include_images", params.optBoolean("include_images", false))
        copy.put("max_chars", params.optInt("max_chars", 24_000))
        val snap = webSnapshot(copy)
        val snapshot = snap.optJSONObject("snapshot") ?: JSONObject()
        return JSONObject()
            .put("ok", snap.optBoolean("ok", false))
            .put("status", snap.optString("status", "failed"))
            .put("session_id", snap.optString("session_id", ""))
            .put("title", snapshot.optString("title", ""))
            .put("url", snapshot.optString("url", ""))
            .put("text", snapshot.optString("text", ""))
            .put("links", snapshot.optJSONArray("links") ?: JSONArray())
            .put("images", snapshot.optJSONArray("images") ?: JSONArray())
    }

    private fun resolveSession(params: JSONObject): Session? {
        val requested = params.optString("session_id", "").trim()
        val id = if (requested.isNotBlank()) requested else activeSessionId
        if (id.isNullOrBlank()) return null
        val session = sessions[id] ?: return null
        activeSessionId = id
        return session
    }

    private fun destroySession(id: String) {
        val session = sessions.remove(id) ?: return
        runOnMainSync {
            try {
                session.webView.stopLoading()
            } catch (_: Exception) {
            }
            try {
                session.webView.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun unsupported(method: String, code: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("method", method)
            .put("error", code)
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        obj.keys().forEach { key ->
            val value = obj.opt(key)
            if (value != null && value != JSONObject.NULL) {
                map[key] = value.toString()
            }
        }
        return map
    }

    private fun evaluateJavascriptSync(webView: WebView, script: String, timeoutMs: Long): String {
        val result = AtomicReference<String>("")
        val error = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        runOnMain {
            try {
                webView.evaluateJavascript(script) { value ->
                    result.set(value ?: "")
                    latch.countDown()
                }
            } catch (e: Throwable) {
                error.set(e)
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        error.get()?.let { throw it }
        return result.get()
    }

    private fun decodeJsValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                JSONObject("{\"v\":$trimmed}").optString("v", trimmed)
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun parseJsonOrFallback(raw: String): JSONObject {
        val decoded = decodeJsValue(raw)
        return try {
            JSONObject(decoded)
        } catch (_: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", "invalid_json")
                .put("raw", decoded)
        }
    }

    private fun escapeForJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun runOnMainSync(timeoutMs: Long = 20_000L, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        mainHandler.post {
            try {
                block()
            } catch (e: Throwable) {
                error.set(e)
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            throw IllegalStateException("main_thread_timeout")
        }
        error.get()?.let { throw it }
    }
}
