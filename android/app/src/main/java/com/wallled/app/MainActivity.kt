package com.wallled.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Hosts the same page as the desktop version in a WebView. Android's WebView has no
 * Web Bluetooth, so the page talks to the strip through [LedBle], exposed as `window.AndroidBle`.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var ble: LedBle
    private var pendingMic: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide() // the page has its own header
        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        ble = LedBle(this) { js -> runOnUiThread { web.evaluateJavascript(js, null) } }
        web.addJavascriptInterface(Bridge(), "AndroidBle")

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) {
                    request.deny()
                } else if (has(Manifest.permission.RECORD_AUDIO)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingMic = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                }
            }
        }

        // An https base URL makes the page a secure context, which getUserMedia requires.
        val html = assets.open("index.html").bufferedReader().use { it.readText() }
        web.loadDataWithBaseURL("https://wallled.app/", html, "text/html", "utf-8", null)

        if (!ble.hasPermissions()) requestPermissions(LedBle.PERMISSIONS, REQ_BT)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == REQ_MIC) {
            val req = pendingMic ?: return
            pendingMic = null
            if (has(Manifest.permission.RECORD_AUDIO)) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else req.deny()
        }
    }

    override fun onDestroy() {
        ble.disconnect()
        web.destroy()
        super.onDestroy()
    }

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    inner class Bridge {
        @JavascriptInterface
        fun connect() {
            if (!ble.hasPermissions()) runOnUiThread { requestPermissions(LedBle.PERMISSIONS, REQ_BT) }
            ble.connect()
        }

        @JavascriptInterface
        fun disconnect() = ble.disconnect()

        @JavascriptInterface
        fun write(hex: String) = ble.write(hex)

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        const val REQ_BT = 1
        const val REQ_MIC = 2
    }
}
