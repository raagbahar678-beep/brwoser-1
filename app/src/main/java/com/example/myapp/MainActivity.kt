package com.example.myapp

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Entry(val title: String, val url: String)

class Tab(val web: WebView) {
    var title: String = ""
    var url: String = ""
    var pending: String? = null
}

class MainActivity : Activity() {

    companion object {
        const val HOME = "https://www.google.com"
        const val REQ_FILE = 1001
        const val MATCH = -1
        const val WRAP = -2
    }

    private val tabs = ArrayList<Tab>()
    private var cur = -1
    private val bookmarks = ArrayList<Entry>()
    private val history = ArrayList<Entry>()
    private var desktop = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var backBtn: Button
    private lateinit var fwdBtn: Button
    private lateinit var reloadBtn: Button
    private lateinit var homeBtn: Button
    private lateinit var tabsBtn: Button
    private lateinit var menuBtn: Button
    private lateinit var goBtn: Button

    private val chromeMajor: String by lazy {
        val m = Regex("Chrome/([0-9]+)").find(WebSettings.getDefaultUserAgent(this))
        if (m != null) m.groupValues[1] + ".0.0.0" else "124.0.0.0"
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("browser", Context.MODE_PRIVATE)
        desktop = prefs.getBoolean("desktop", false)
        loadEntries("bookmarks", bookmarks)
        loadEntries("history", history)
        buildUi()
        setContentView(root)
        restoreTabs()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        CookieManager.getInstance().flush()
        // NOTE: we deliberately do NOT call web.onPause() here. It freezes JavaScript and
        // network streaming, which breaks Claude's streaming replies and causes retries.
    }

    override fun onResume() {
        super.onResume()
        curTab()?.web?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (t in tabs) {
            t.web.stopLoading()
            t.web.destroy()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustom()
            return
        }
        val t = curTab()
        if (t != null && t.web.canGoBack()) {
            t.web.goBack()
            return
        }
        if (tabs.size > 1) {
            closeTab(cur)
            return
        }
        super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            if (cb != null) {
                cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            }
        }
    }

    // ---------------------------------------------------------------- keyboard + mouse

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && customView == null) {
            if (handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && (ev.metaState and KeyEvent.META_CTRL_ON) != 0) {
            val w = curTab()?.web
            if (w != null) {
                if (ev.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f) w.zoomIn() else w.zoomOut()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val ctrl = e.isCtrlPressed
        val alt = e.isAltPressed
        val w = curTab()?.web
        if (ctrl) {
            when (code) {
                KeyEvent.KEYCODE_L -> { focusUrl(); return true }
                KeyEvent.KEYCODE_T -> { addTab(HOME, true); return true }
                KeyEvent.KEYCODE_W -> { closeTab(cur); return true }
                KeyEvent.KEYCODE_R -> { w?.reload(); return true }
                KeyEvent.KEYCODE_D -> { addBookmark(); return true }
                KeyEvent.KEYCODE_B -> { showEntries("Bookmarks", bookmarks, true); return true }
                KeyEvent.KEYCODE_H -> { showEntries("History", history, false); return true }
                KeyEvent.KEYCODE_J -> { openDownloads(); return true }
                KeyEvent.KEYCODE_TAB -> { nextTab(if (e.isShiftPressed) -1 else 1); return true }
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { w?.zoomIn(); return true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { w?.zoomOut(); return true }
                else -> { }
            }
        }
        if (alt) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { goBack(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { goForward(); return true }
                KeyEvent.KEYCODE_HOME -> { goHome(); return true }
                else -> { }
            }
        }
        when (code) {
            KeyEvent.KEYCODE_F5 -> { w?.reload(); return true }
            KeyEvent.KEYCODE_F6 -> { focusUrl(); return true }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (urlBar.hasFocus()) {
                    val t = curTab()
                    if (t != null) urlBar.setText(t.web.url ?: t.url)
                    hideKeyboard()
                    w?.requestFocus()
                } else {
                    w?.stopLoading()
                }
                return true
            }
            else -> { }
        }
        return false
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setAllCaps(false)
        b.textSize = 12f
        b.isFocusable = false
        b.isFocusableInTouchMode = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = dp(44)
        b.minimumHeight = dp(44)
        b.setPadding(dp(2), 0, dp(2), 0)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        backBtn = btn("Back") { goBack() }
        fwdBtn = btn("Fwd") { goForward() }
        reloadBtn = btn("Reload") { reloadOrStop() }
        homeBtn = btn("Home") { goHome() }
        tabsBtn = btn("Tabs 1") { showTabs() }
        menuBtn = btn("Menu") { showMenu() }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val all = listOf(backBtn, fwdBtn, reloadBtn, homeBtn, tabsBtn, menuBtn)
        for (b in all) {
            bar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        urlBar = EditText(this)
        urlBar.setSingleLine(true)
        urlBar.textSize = 15f
        urlBar.hint = "Search Google or type a URL"
        urlBar.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        urlBar.setSelectAllOnFocus(true)
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(urlBar.text.toString())
                true
            } else {
                false
            }
        }
        goBtn = btn("Go") { go(urlBar.text.toString()) }

        val addr = LinearLayout(this)
        addr.orientation = LinearLayout.HORIZONTAL
        addr.setPadding(dp(4), 0, dp(4), 0)
        addr.addView(urlBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        addr.addView(goBtn, LinearLayout.LayoutParams(dp(56), WRAP))
        root.addView(addr, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.INVISIBLE
        root.addView(progress, LinearLayout.LayoutParams(MATCH, dp(3)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun focusUrl() {
        urlBar.requestFocus()
        urlBar.selectAll()
    }

    private fun updateTabsBtn() {
        tabsBtn.text = "Tabs " + tabs.size
    }

    // ---------------------------------------------------------------- navigation

    private fun resolve(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return HOME
        val low = s.lowercase()
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("about:")) return s
        if (!s.contains(" ") && (s.contains(".") || low.startsWith("localhost"))) return "https://" + s
        return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
    }

    private fun go(raw: String) {
        val w = curTab()?.web ?: return
        w.loadUrl(resolve(raw))
        hideKeyboard()
        w.requestFocus()
    }

    private fun goBack() {
        val w = curTab()?.web ?: return
        if (w.canGoBack()) w.goBack()
    }

    private fun goForward() {
        val w = curTab()?.web ?: return
        if (w.canGoForward()) w.goForward()
    }

    private fun goHome() {
        val w = curTab()?.web ?: return
        w.loadUrl(HOME)
    }

    private fun reloadOrStop() {
        val w = curTab()?.web ?: return
        if (reloadBtn.text.toString() == "Stop") {
            w.stopLoading()
            reloadBtn.text = "Reload"
            progress.visibility = View.INVISIBLE
        } else {
            w.reload()
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_VIEW) {
            val d = i.data
            if (d != null) addTab(d.toString(), true)
        }
    }

    private fun openExternal(u: String) {
        try {
            val i: Intent = if (u.startsWith("intent:")) {
                Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(u))
            }
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
            toast("No app can open this link")
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun curTab(): Tab? {
        return if (cur >= 0 && cur < tabs.size) tabs[cur] else null
    }

    private fun isCur(t: Tab): Boolean {
        return cur >= 0 && cur < tabs.size && tabs[cur] === t
    }

    private fun uaString(): String {
        return if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeMajor + " Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeMajor + " Mobile Safari/537.36"
        }
    }

    private fun addTab(url: String?, select: Boolean): Tab {
        val w = WebView(this)
        val tab = Tab(w)
        setupWebView(w, tab)
        tabs.add(tab)
        if (url != null) {
            tab.url = url
            w.loadUrl(url)
        }
        if (select) selectTab(tabs.size - 1) else updateTabsBtn()
        return tab
    }

    private fun selectTab(i: Int) {
        if (i < 0 || i >= tabs.size) return
        cur = i
        val t = tabs[i]
        content.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        content.addView(t.web, FrameLayout.LayoutParams(MATCH, MATCH))
        t.web.onResume()
        val p = t.pending
        if (p != null) {
            t.pending = null
            t.web.loadUrl(p)
        }
        urlBar.setText(t.web.url ?: t.url)
        progress.visibility = View.INVISIBLE
        reloadBtn.text = "Reload"
        updateTabsBtn()
        t.web.requestFocus()
    }

    private fun recoverTab(dead: WebView) {
        val idx = tabs.indexOfFirst { it.web === dead }
        if (idx < 0) return
        val old = tabs[idx]
        val u = if (old.url.startsWith("http")) old.url else HOME
        val nt = Tab(WebView(this))
        setupWebView(nt.web, nt)
        nt.url = u
        nt.title = u
        nt.pending = u
        tabs[idx] = nt
        content.removeView(old.web)
        old.web.destroy()
        if (idx == cur) selectTab(idx)
        toast("Page crashed, reloading")
    }

    private fun closeTab(i: Int) {
        if (i < 0 || i >= tabs.size) return
        val t = tabs.removeAt(i)
        content.removeView(t.web)
        t.web.stopLoading()
        t.web.destroy()
        if (tabs.isEmpty()) {
            cur = -1
            addTab(HOME, true)
            return
        }
        if (i < cur) {
            cur -= 1
            updateTabsBtn()
        } else if (i == cur) {
            cur = -1
            selectTab(minOf(i, tabs.size - 1))
        } else {
            updateTabsBtn()
        }
    }

    private fun nextTab(d: Int) {
        val n = tabs.size
        if (n < 2) return
        selectTab(((cur + d) % n + n) % n)
    }

    private fun showTabs() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        val sv = ScrollView(this)
        sv.addView(box)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Tabs (" + tabs.size + ")")
            .setPositiveButton("New tab") { _, _ -> addTab(HOME, true) }
            .setNegativeButton("Close", null)
            .create()

        for (i in 0 until tabs.size) {
            val t = tabs[i]
            val shown = t.web.title
            var label = if (!shown.isNullOrEmpty()) shown else if (t.title.isNotEmpty()) t.title else t.url
            if (label.isEmpty()) label = "New tab"
            if (label.length > 45) label = label.substring(0, 45)
            if (i == cur) label = "* " + label

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val tv = TextView(this)
            tv.text = label
            tv.textSize = 15f
            tv.setPadding(dp(8), dp(12), dp(8), dp(12))
            tv.setOnClickListener {
                dlg.dismiss()
                selectTab(i)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))

            val x = Button(this)
            x.text = "X"
            x.setOnClickListener {
                dlg.dismiss()
                closeTab(i)
                showTabs()
            }
            row.addView(x, LinearLayout.LayoutParams(dp(56), WRAP))
            box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        dlg.setView(sv)
        dlg.show()
    }

    // ---------------------------------------------------------------- WebView

    private fun setupWebView(w: WebView, tab: Tab) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = uaString()

        // WebView normally adds an "X-Requested-With: <package name>" header to every request,
        // which Chrome never sends. Remove it so claude.ai treats this like normal Chrome.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
        } catch (e: Throwable) {
        }

        // Keep the page's renderer at high priority even when the app is in the background,
        // so a streaming Claude reply is less likely to be killed by Android.
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:") ||
                    u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("javascript:")) {
                    return false
                }
                openExternal(u)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == null) return
                tab.url = url
                if (isCur(tab) && !urlBar.hasFocus()) urlBar.setText(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url == null || url == "about:blank") return
                tab.url = url
                if (isCur(tab) && !urlBar.hasFocus()) urlBar.setText(url)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val dead = view ?: return false
                if (tabs.none { it.web === dead }) return false
                content.post { recoverTab(dead) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                val u = url ?: return
                tab.url = u
                val t = view?.title ?: u
                tab.title = if (t.isEmpty()) u else t
                addHistory(tab.title, u)
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!isCur(tab)) return
                progress.progress = newProgress
                if (newProgress >= 100) {
                    progress.visibility = View.INVISIBLE
                    reloadBtn.text = "Reload"
                } else {
                    progress.visibility = View.VISIBLE
                    reloadBtn.text = "Stop"
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (title != null) tab.title = title
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val nt = addTab(null, true)
                transport.webView = nt.web
                msg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                content.post {
                    val idx = tabs.indexOfFirst { it.web === window }
                    if (idx >= 0) closeTab(idx)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent: Intent = try {
                    fileChooserParams?.createIntent() ?: fallbackPicker()
                } catch (e: Exception) {
                    fallbackPicker()
                }
                try {
                    startActivityForResult(intent, REQ_FILE)
                } catch (e: Exception) {
                    fileCallback = null
                    filePathCallback?.onReceiveValue(null)
                    return false
                }
                return true
            }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                showCustom(view, callback)
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }

        w.setDownloadListener(DownloadListener { url, ua, cd, mime, _ ->
            startDownload(url, ua, cd, mime)
        })
    }

    private fun fallbackPicker(): Intent {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        return i
    }

    @Suppress("DEPRECATION")
    private fun showCustom(v: View, cb: WebChromeClient.CustomViewCallback?) {
        customView = v
        customCb = cb
        val decor = window.decorView as FrameLayout
        decor.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        root.visibility = View.GONE
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @Suppress("DEPRECATION")
    private fun hideCustom() {
        val v = customView ?: return
        val decor = window.decorView as FrameLayout
        decor.removeView(v)
        customView = null
        root.visibility = View.VISIBLE
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        customCb?.onCustomViewHidden()
        customCb = null
    }

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) {
            toast("This download type is not supported")
            return
        }
        try {
            val req = DownloadManager.Request(Uri.parse(url))
            if (mime != null) req.setMimeType(mime)
            val ck = CookieManager.getInstance().getCookie(url)
            if (ck != null) req.addRequestHeader("Cookie", ck)
            if (ua != null) req.addRequestHeader("User-Agent", ua)
            val name = URLUtil.guessFileName(url, cd, mime)
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("Downloading " + name)
        } catch (e: Exception) {
            toast("Download failed")
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("Cannot open downloads")
        }
    }

    // ---------------------------------------------------------------- menu, bookmarks, history

    private fun showMenu() {
        val items = arrayOf(
            "New tab",
            "Bookmarks",
            "Add bookmark",
            "History",
            if (desktop) "Desktop site: ON (tap to switch off)" else "Desktop site: OFF (tap to switch on)",
            "Downloads",
            "Share page link",
            "Clear cookies, logins and history",
            "Close this tab"
        )
        val b = AlertDialog.Builder(this)
        b.setItems(items) { _, which ->
            when (which) {
                0 -> { addTab(HOME, true) }
                1 -> { showEntries("Bookmarks", bookmarks, true) }
                2 -> { addBookmark() }
                3 -> { showEntries("History", history, false) }
                4 -> { toggleDesktop() }
                5 -> { openDownloads() }
                6 -> { shareLink() }
                7 -> { confirmClear() }
                8 -> { closeTab(cur) }
                else -> { }
            }
        }
        b.setNegativeButton("Close", null)
        b.show()
    }

    private fun toggleDesktop() {
        desktop = !desktop
        prefs.edit().putBoolean("desktop", desktop).apply()
        for (t in tabs) {
            t.web.settings.userAgentString = uaString()
        }
        curTab()?.web?.reload()
        toast(if (desktop) "Desktop site on" else "Mobile site on")
    }

    private fun shareLink() {
        val t = curTab() ?: return
        val u = t.web.url ?: t.url
        if (u.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, u)
        startActivity(Intent.createChooser(i, "Share link"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear data?")
            .setMessage("This signs you out of all sites and clears history.")
            .setPositiveButton("Clear") { _, _ -> clearData() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        curTab()?.web?.clearCache(true)
        history.clear()
        saveEntries("history", history)
        toast("Cleared")
    }

    private fun addBookmark() {
        val t = curTab() ?: return
        val u = t.web.url ?: t.url
        if (!u.startsWith("http")) {
            toast("Nothing to bookmark")
            return
        }
        for (e in bookmarks) {
            if (e.url == u) {
                toast("Already bookmarked")
                return
            }
        }
        val title = if (t.title.isEmpty()) u else t.title
        bookmarks.add(0, Entry(title, u))
        saveEntries("bookmarks", bookmarks)
        toast("Bookmark added")
    }

    private fun addHistory(title: String, url: String) {
        if (!url.startsWith("http")) return
        if (history.isNotEmpty() && history[0].url == url) return
        history.add(0, Entry(title, url))
        while (history.size > 500) {
            history.removeAt(history.size - 1)
        }
    }

    private fun showEntries(title: String, list: ArrayList<Entry>, isBookmarks: Boolean) {
        if (list.isEmpty()) {
            toast("Nothing here yet")
            return
        }
        val key = if (isBookmarks) "bookmarks" else "history"
        val labels = Array(list.size) { i -> list[i].title + "\n" + list[i].url }
        val b = AlertDialog.Builder(this)
        b.setTitle(title + (if (isBookmarks) " (long-press to delete)" else " (long-press = new tab)"))
        b.setItems(labels) { d, which ->
            d.dismiss()
            val e = list[which]
            curTab()?.web?.loadUrl(e.url)
        }
        b.setNegativeButton("Close", null)
        b.setNeutralButton("Clear all") { _, _ ->
            list.clear()
            saveEntries(key, list)
        }
        val dlg = b.create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < list.size) {
                if (isBookmarks) {
                    list.removeAt(pos)
                    saveEntries(key, list)
                    dlg.dismiss()
                    showEntries(title, list, isBookmarks)
                } else {
                    val e = list[pos]
                    dlg.dismiss()
                    addTab(e.url, true)
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun loadEntries(key: String, list: ArrayList<Entry>) {
        list.clear()
        try {
            val s = prefs.getString(key, "[]") ?: "[]"
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(Entry(o.getString("t"), o.getString("u")))
            }
        } catch (e: Exception) {
        }
    }

    private fun saveEntries(key: String, list: ArrayList<Entry>) {
        val a = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("t", e.title)
            o.put("u", e.url)
            a.put(o)
        }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun saveSession() {
        val a = JSONArray()
        for (t in tabs) {
            val p = t.pending
            val u: String = if (p != null) p else (t.web.url ?: t.url)
            if (u.isNotEmpty()) a.put(u)
        }
        prefs.edit()
            .putString("tabs", a.toString())
            .putInt("cur", cur)
            .putBoolean("desktop", desktop)
            .apply()
        saveEntries("history", history)
        saveEntries("bookmarks", bookmarks)
    }

    private fun restoreTabs() {
        var any = false
        try {
            val s = prefs.getString("tabs", "[]") ?: "[]"
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val u = a.getString(i)
                val t = addTab(null, false)
                t.url = u
                t.title = u
                t.pending = u
                any = true
            }
        } catch (e: Exception) {
        }
        if (!any || tabs.isEmpty()) {
            addTab(HOME, true)
            return
        }
        var c = prefs.getInt("cur", 0)
        if (c < 0 || c >= tabs.size) c = 0
        selectTab(c)
    }
}
