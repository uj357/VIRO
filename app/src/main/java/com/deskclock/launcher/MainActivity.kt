package com.deskclock.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        immersive()
    }

    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) immersive() }

    @Deprecated("home launcher: never exit")
    override fun onBackPressed() { web.evaluateJavascript("window.onBack&&onBack()", null) }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        web.evaluateJavascript("window.onBack&&onBack(true)", null) // زر الهوم يرجع للرئيسية
    }

    private fun b64(bm: Bitmap, f: Bitmap.CompressFormat, q: Int): String {
        val o = ByteArrayOutputStream(); bm.compress(f, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    inner class Bridge {
        private fun ctl(): MediaController? = try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val l = m.getActiveSessions(ComponentName(this@MainActivity, NLService::class.java))
            l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
        } catch (e: Exception) { null }

        private fun go(i: Intent): Boolean = try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); true } catch (e: Exception) { false }

        @JavascriptInterface fun hasAccess(): Boolean =
            (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

        @JavascriptInterface fun openAccess() { go(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }

        @JavascriptInterface fun state(): String {
            val c = ctl() ?: return "{}"
            val md = c.metadata ?: return "{}"
            val ps = c.playbackState
            var pos = ps?.position ?: 0L
            if (ps?.state == PlaybackState.STATE_PLAYING)
                pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
            return JSONObject()
                .put("title", md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("artist", md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
                .put("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION))
                .put("pos", pos)
                .put("playing", ps?.state == PlaybackState.STATE_PLAYING)
                .put("pkg", c.packageName).toString()
        }

        @JavascriptInterface fun art(): String {
            val md = ctl()?.metadata ?: return ""
            val bm = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return ""
            val w = minOf(bm.width, 640); val h = bm.height * w / bm.width
            return "data:image/jpeg;base64," + b64(Bitmap.createScaledBitmap(bm, w, h, true), Bitmap.CompressFormat.JPEG, 88)
        }

        @JavascriptInterface fun cmd(a: String) {
            val t = ctl()?.transportControls ?: return
            when (a) { "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "prev" -> t.skipToPrevious() }
        }

        @JavascriptInterface fun seek(ms: Long) { ctl()?.transportControls?.seekTo(ms) }

        // شفل/تكرار: بس إذا التطبيق بيعرضهم كـ custom action
        @JavascriptInterface fun custom(k: String) {
            val c = ctl() ?: return
            c.playbackState?.customActions
                ?.firstOrNull { it.action.contains(k, true) || it.name.toString().contains(k, true) }
                ?.let { c.transportControls.sendCustomAction(it, null) }
        }

        @JavascriptInterface fun launch(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            return go(i)
        }

        @JavascriptInterface fun apps(): String {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            packageManager.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                .forEach { arr.put(JSONObject().put("n", it.loadLabel(packageManager).toString()).put("p", it.activityInfo.packageName)) }
            return arr.toString()
        }

        @JavascriptInterface fun icon(pkg: String): String = try {
            val d = packageManager.getApplicationIcon(pkg)
            val bm = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 144, 144); d.draw(Canvas(bm))
            "data:image/png;base64," + b64(bm, Bitmap.CompressFormat.PNG, 100)
        } catch (e: Exception) { "" }

        @JavascriptInterface fun sys(a: String) {
            val act = when (a) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "home" -> Settings.ACTION_HOME_SETTINGS
                "date" -> Settings.ACTION_DATE_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            go(Intent(act))
        }

        @JavascriptInterface fun brightness(p: Int) = runOnUiThread {
            val lp = window.attributes; lp.screenBrightness = p.coerceIn(3, 100) / 100f; window.attributes = lp
        }

        @JavascriptInterface fun volume(p: Int) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * p / 100, 0)
        }

        @JavascriptInterface fun getVolume(): Int {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
    }
}
