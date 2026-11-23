package com.iqrasmart.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null

    @Volatile private var ttsReady = false
    @Volatile private var arabicAvailable = false
    @Volatile private var ttsInitialized = false
    private data class SpeechRequest(val text: String, val rate: Float, val pitch: Float, val id: Int)
    private var pending: SpeechRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(Bridge(), "AndroidTTS")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        tts = TextToSpeech(this) { status ->
            ttsInitialized = true
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false
                arabicAvailable = false
                pending?.let { finishUtterance(it.id.toString()) }
                pending = null
                notifyTtsStatus()
                return@TextToSpeech
            }

            val engine = tts ?: return@TextToSpeech
            val locales = listOf(Locale.forLanguageTag("ar-SA"), Locale("ar"))
            var selected = false
            for (locale in locales) {
                val availability = engine.isLanguageAvailable(locale)
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    val result = engine.setLanguage(locale)
                    if (result >= TextToSpeech.LANG_AVAILABLE) {
                        selected = true
                        break
                    }
                }
            }

            arabicAvailable = selected
            ttsReady = selected
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finishUtterance(utteranceId)
                override fun onError(utteranceId: String?) = finishUtterance(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finishUtterance(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finishUtterance(utteranceId)
            })

            if (selected) {
                pending?.let { speakNow(it) }
            } else {
                pending?.let { finishUtterance(it.id.toString()) }
            }
            pending = null
            notifyTtsStatus()
        }
    }

    private fun notifyTtsStatus() {
        if (!::webView.isInitialized) return
        val ready = ttsReady && arabicAvailable
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript("window.__ttsStatus&&window.__ttsStatus($ready)", null)
            }
        }
    }

    private fun finishUtterance(id: String?) {
        val numericId = id?.toIntOrNull() ?: return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript("window.__ttsDone&&window.__ttsDone($numericId)", null)
            }
        }
    }

    private fun speakNow(request: SpeechRequest) {
        val engine = tts ?: run {
            finishUtterance(request.id.toString())
            return
        }
        if (!ttsReady || !arabicAvailable) {
            finishUtterance(request.id.toString())
            return
        }
        engine.setSpeechRate(request.rate.coerceIn(0.5f, 2.0f))
        engine.setPitch(request.pitch.coerceIn(0.5f, 2.0f))
        val result = engine.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            request.id.toString()
        )
        if (result == TextToSpeech.ERROR) finishUtterance(request.id.toString())
    }

    inner class Bridge {
        @JavascriptInterface
        fun isReady(): Boolean = ttsReady

        @JavascriptInterface
        fun isArabicAvailable(): Boolean = arabicAvailable

        @JavascriptInterface
        fun speak(text: String, rate: Double, pitch: Double, id: Int) {
            val request = SpeechRequest(text, rate.toFloat(), pitch.toFloat(), id)
            runOnUiThread {
                if (ttsReady && arabicAvailable) {
                    pending = null
                    speakNow(request)
                } else if (!ttsInitialized) {
                    // Keep only the latest request while the Android speech engine initializes.
                    pending = request
                } else {
                    finishUtterance(id.toString())
                }
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                pending?.let { finishUtterance(it.id.toString()) }
                pending = null
                tts?.stop()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        pending = null
        ttsReady = false
        arabicAvailable = false
        ttsInitialized = true
        tts?.stop()
        tts?.shutdown()
        webView.destroy()
        super.onDestroy()
    }
}
