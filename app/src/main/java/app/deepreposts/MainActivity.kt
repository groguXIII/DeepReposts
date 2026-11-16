package app.deepreposts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

const val UPDATE_REPO = "groguXIII/DeepReposts"   // откуда приложение берёт обновления
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val LIMIT = 2500            // удалений в сутки
const val LOAD_LIMIT = 50000      // максимум загружаемых репостов
const val DAY_MS = 24L * 60 * 60 * 1000
const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
val BG = Color.parseColor("#0A1428")
val PANEL = Color.parseColor("#13264A")
val ICE = Color.parseColor("#CFEFFF")
val RED = Color.parseColor("#E23B52")
val GOLD = Color.parseColor("#F4C95D")

class Item(val url: String, val id: String, val cover: String)

/** Квадратная плитка: превью + кружок-галочка. */
class Cell(c: Context) : FrameLayout(c) {
    val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(PANEL) }
    val scrim = View(c).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE }
    val badge = TextView(c).apply { gravity = Gravity.CENTER; textSize = 14f }

    init {
        val d = c.resources.displayMetrics.density
        val pad = (3 * d).toInt()
        setPadding(pad, pad, pad, pad)
        val b = (26 * d).toInt()
        addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(badge, FrameLayout.LayoutParams(b, b, Gravity.END or Gravity.TOP).apply { setMargins(b / 4, b / 4, b / 4, b / 4) })
    }

    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

class MainActivity : Activity() {
    private enum class Mode { LOGIN, RESOLVE, COLLECT, DELETE, IDLE }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var appLayer: FrameLayout
    private lateinit var settingsView: LinearLayout
    private lateinit var rv: RecyclerView
    private lateinit var header: LinearLayout
    private lateinit var avatar: ImageView
    private lateinit var tvUser: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvSel: TextView
    private lateinit var tvDel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvUpd: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnDelete: Button
    private lateinit var loginBar: LinearLayout
    private lateinit var btnSpeed: Button
    private lateinit var btnUa: Button
    private var lastLoginNet = ""
    private lateinit var verifyCard: LinearLayout
    private lateinit var tvVerify: TextView
    private lateinit var verifySpin: ProgressBar
    private lateinit var btnVerifyRetry: Button
    private lateinit var btnVerifyRelogin: Button
    private lateinit var tvLoginInfo: TextView

    private var mode = Mode.LOGIN
    private var armed = false
    private var running = false
    private var serviceOn = false
    private var tabSeen = false
    private var sessionSeenAt = 0L
    private var verifyStart = 0L
    private val logLines = ArrayList<String>()
    private var fastMode = false
    private var tpl: JSONObject? = null
    private var fastIdx = 0
    private var recv = 0
    private var sentCnt = 0
    private var okCount = 0
    private var failCount = 0
    private var errRun = 0
    private var pauses = 0
    private var delayMul = 1.0
    private var pauseUntil = 0L
    private var delPending = 0
    private var lastFlush = 0L
    private var lastSendAt = 0L
    private var lastLogged = 0L
    private var diagCount = 0
    private var user = ""
    private var qi = 0
    private var tries = 0
    private var fails = 0
    private var lastNote = 0L
    private var lastGrowth = 0L
    private var queue: List<Item> = emptyList()
    private val items = ArrayList<Item>()
    private val seen = HashSet<String>()
    private val unchecked = HashSet<String>()   // галочка СНЯТА = репост будет удалён. Новые репосты по умолчанию с галочкой.
    private val gridAdapter = RepostAdapter()

    // ---------- жизненный цикл ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("dr", MODE_PRIVATE)
        unchecked.addAll((prefs.getString("unchecked", "") ?: "").split(",").filter { it.isNotEmpty() })
        CookieManager.getInstance().setAcceptCookie(true)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        web = makeWeb()
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))   // работает «под» оформлением

        appLayer = FrameLayout(this)
        appLayer.addView(BgView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        appLayer.addView(SnowView(this), FrameLayout.LayoutParams(MATCH, MATCH))   // снег ЗА интерфейсом
        buildMain()
        appLayer.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
        settingsView = buildSettings()
        settingsView.visibility = View.GONE
        appLayer.addView(settingsView, FrameLayout.LayoutParams(MATCH, MATCH))
        verifyCard = buildVerifyCard()
        verifyCard.visibility = View.GONE
        appLayer.addView(verifyCard, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })
        appLayer.visibility = View.GONE
        root.addView(appLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        loginBar = buildLoginBar()
        root.addView(loginBar, FrameLayout.LayoutParams(MATCH, dp(132), Gravity.BOTTOM))
        setContentView(root)

        askNotifications()
        if (prefs.getInt("schema", 0) < 2) prefs.edit().remove("user").remove("avatar").putInt("schema", 2).apply()
        user = prefs.getString("user", "") ?: ""
        val cached = prefs.getString("avatar", "") ?: ""
        if (cached.isNotEmpty()) showAvatar(cached)
        if (user.isNotEmpty()) startVerify() else showLogin()   // каждый запуск сверяем аккаунт с TikTok
    }

    override fun onPause() { CookieManager.getInstance().flush(); super.onPause() }

    override fun onStop() { CookieManager.getInstance().flush(); super.onStop() }

    override fun onResume() { super.onResume(); if (::rv.isInitialized && mode == Mode.IDLE) refresh() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        noteStop()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            settingsView.visibility == View.VISIBLE -> closeSettings()
            mode == Mode.LOGIN && web.canGoBack() -> web.goBack()
            running || mode == Mode.COLLECT || mode == Mode.RESOLVE -> moveTaskToBack(true)   // не закрываем — работа продолжается
            else -> super.onBackPressed()
        }
    }

    // ---------- встроенный браузер ----------
    private fun makeWeb(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = true   // окна проверки/капчи при входе не должны блокироваться
        settings.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) { pageDone() }

            // видео и картинки браузеру не нужны: так страницы грузятся в разы быстрее и не тратят трафик
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (mode == Mode.COLLECT || mode == Mode.DELETE || mode == Mode.RESOLVE) {
                    val u = r.url.toString()
                    if (u.contains(".mp4") || u.contains("mime_type=video") || u.contains("/video/tos/")) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return null
            }

            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                ui.post { rebuildWeb() }
                return true
            }
        }
    }

    private fun rebuildWeb() {
        val idx = root.indexOfChild(web)
        root.removeView(web)
        web.destroy()
        web = makeWeb()
        root.addView(web, if (idx < 0) 0 else idx, FrameLayout.LayoutParams(MATCH, MATCH))
        webMargin(if (mode == Mode.LOGIN) dp(132) else 0)
        web.settings.blockNetworkImage = mode == Mode.COLLECT || mode == Mode.DELETE || mode == Mode.RESOLVE
        when {
            running -> { armed = true; tries = 0; web.loadUrl(queue[qi].url) }
            mode == Mode.COLLECT -> failCollect("Окно браузера было перезапущено системой. Откройте настройки и нажмите «Обновить список репостов».")
            mode == Mode.LOGIN -> { web.settings.userAgentString = loginUa(); web.loadUrl("https://www.tiktok.com/login") }
            mode == Mode.RESOLVE -> startVerify()
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        web.settings.blockNetworkImage = m == Mode.COLLECT || m == Mode.DELETE || m == Mode.RESOLVE
    }

    private fun pageDone() {
        if (!armed) return
        if (mode == Mode.COLLECT) { armed = false; ui.postDelayed({ collectTick() }, 800) }
        else if (mode == Mode.DELETE) { armed = false; ui.postDelayed({ removeTick() }, 1200) }
    }

    // ---------- вход ----------
    private fun cookieString(): String {
        val cm = CookieManager.getInstance()
        val sb = StringBuilder()
        for (u in listOf("https://www.tiktok.com", "https://tiktok.com", "https://m.tiktok.com", web.url ?: "")) {
            if (u.startsWith("http")) sb.append(cm.getCookie(u) ?: "").append("; ")
        }
        return sb.toString()
    }

    private fun loginUa(): String = if (prefs.getBoolean("loginMobile", true)) MOBILE_UA else DESKTOP_UA

    private fun updateUaButton() {
        btnUa.text = if (prefs.getBoolean("loginMobile", true)) "Режим: телефон" else "Режим: компьютер"
    }

    private fun toggleLoginUa() {
        if (mode != Mode.LOGIN) return
        prefs.edit().putBoolean("loginMobile", !prefs.getBoolean("loginMobile", true)).apply()
        updateUaButton()
        web.settings.userAgentString = loginUa()
        dlog("режим входа: " + (if (prefs.getBoolean("loginMobile", true)) "телефон" else "компьютер"))
        web.loadUrl("https://www.tiktok.com/login")
    }

    private fun sessionOk(): Boolean {
        val c = cookieString()
        return c.contains("sessionid=") || c.contains("sid_tt=") || c.contains("sid_guard=") || c.contains("uid_tt=")
    }

    // только ИМЕНА cookies (без значений) — для диагностики на экране входа
    private fun cookieNames(): String =
        cookieString().split(";").map { it.trim().substringBefore("=") }.filter { it.isNotEmpty() }.distinct().take(40).joinToString(", ").ifEmpty { "нет" }

    private fun webMargin(px: Int) {
        web.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = px }
    }

    // Пока идёт вход, приложение НИЧЕГО не трогает в браузере: только смотрит, появилась ли сессия.
    private val poll = object : Runnable {
        override fun run() {
            if (mode != Mode.LOGIN) return
            CookieManager.getInstance().flush()
            // что отвечает сервер входа TikTok (код ошибки/описание) — попадает в журнал и в панель
            web.evaluateJavascript(Scripts.LOGIN_NET) { r ->
                try {
                    val arr = JSONArray(r ?: "[]")
                    for (i in 0 until arr.length()) { lastLoginNet = arr.getString(i); dlog("вход: $lastLoginNet") }
                } catch (e: Exception) { }
            }
            val ok = sessionOk()
            tvLoginInfo.text = (if (ok) "Вход обнаружен — открываю приложение…" else "Войдите в TikTok на странице выше. После входа приложение откроется само.") +
                (if (lastLoginNet.isNotEmpty()) "\nответ TikTok: " + lastLoginNet.take(110) else "") +
                "\ncookies: " + cookieNames().take(90)
            if (ok) {
                if (sessionSeenAt == 0L) sessionSeenAt = System.currentTimeMillis()
                // даём TikTok 3 секунды закончить собственные переходы после входа
                if (System.currentTimeMillis() - sessionSeenAt > 3000) { startVerify(); return }
            } else sessionSeenAt = 0L
            ui.postDelayed(this, 1000)
        }
    }

    /** Кнопка внизу экрана входа. */
    private fun manualLogin() { if (mode == Mode.LOGIN) startVerify() }

    // Проверка входа: браузер скрыт, поверх — интерфейс приложения с карточкой. Главная TikTok загружается
    // заново, и у неё спрашивают, чей это аккаунт. Имя НИКОГДА не вводится вручную.
    private fun startVerify() {
        ui.removeCallbacks(poll)
        setMode(Mode.RESOLVE)
        verifyStart = System.currentTimeMillis()
        showApp()
        tvUser.text = "…"
        verifyCard.visibility = View.VISIBLE
        verifySpin.visibility = View.VISIBLE
        btnVerifyRetry.visibility = View.GONE
        btnVerifyRelogin.visibility = View.GONE
        tvVerify.text = "Проверяю вход в TikTok…"
        note("Вход в TikTok…")
        dlog("проверка входа: загружаю главную TikTok")
        web.settings.userAgentString = DESKTOP_UA
        web.loadUrl("https://www.tiktok.com/foryou")
        ui.removeCallbacks(verifyTick); ui.postDelayed(verifyTick, 2500)
    }

    private val verifyTick = object : Runnable {
        override fun run() {
            if (mode != Mode.RESOLVE) return
            web.evaluateJavascript(Scripts.WHOAMI) { r ->
                if (mode != Mode.RESOLVE) return@evaluateJavascript
                val o = try { JSONObject(r ?: "") } catch (e: Exception) { null }
                val name = o?.optString("name") ?: ""
                if (name.isNotEmpty()) {
                    dlog("аккаунт определён: @" + name + " (" + o?.optString("how") + ")")
                    loggedIn(name)
                } else if (System.currentTimeMillis() - verifyStart > 22000) {
                    failVerify(o?.optString("how") ?: "страница не ответила", o?.optString("url") ?: "")
                } else ui.postDelayed(this, 1000)
            }
        }
    }

    private fun failVerify(how: String, path: String) {
        val txt = "Не удалось определить аккаунт.\nСессия: " + (if (sessionOk()) "есть" else "нет") +
            "\ncookies: " + cookieNames() + "\nСтраница: " + path + "\n" + how
        dlog("вход не определён: " + txt.replace("\n", " | "))
        verifySpin.visibility = View.GONE
        tvVerify.text = txt
        btnVerifyRetry.visibility = View.VISIBLE
        btnVerifyRelogin.visibility = View.VISIBLE
    }

    private fun buildVerifyCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = round(PANEL, 20); setPadding(dp(20), dp(20), dp(20), dp(16)); isClickable = true
        }
        card.addView(TextView(this).apply { text = "Вход в TikTok"; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(ICE) })
        tvVerify = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        card.addView(tvVerify, lp(10))
        verifySpin = ProgressBar(this)
        card.addView(verifySpin, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(12) })
        btnVerifyRetry = button("Я вошёл — проверить ещё раз", true).apply { setOnClickListener { startVerify() } }
        card.addView(btnVerifyRetry, lp(14, dp(48)))
        btnVerifyRelogin = button("Войти заново", false).apply { setOnClickListener { showLogin() } }
        card.addView(btnVerifyRelogin, lp(8, dp(48)))
        card.addView(button("Журнал отладки", false).apply { textSize = 13f; setOnClickListener { showLog() } }, lp(8, dp(44)))
        return card
    }

    private fun buildLoginBar(): LinearLayout {
        val lb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        tvLoginInfo = TextView(this).apply { textSize = 11f; setTextColor(ICE); maxLines = 5; text = "Войдите в TikTok на странице выше. После входа приложение откроется само." }
        lb.addView(tvLoginInfo, LinearLayout.LayoutParams(MATCH, WRAP))
        val row = LinearLayout(this)
        btnUa = button("", false).apply { textSize = 12f; setOnClickListener { toggleLoginUa() } }
        row.addView(btnUa, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(button("Журнал", false).apply { textSize = 12f; setOnClickListener { showLog() } },
            LinearLayout.LayoutParams(0, dp(44), 0.8f).apply { leftMargin = dp(6) })
        row.addView(button("Я вошёл(а)", true).apply { textSize = 13f; setOnClickListener { manualLogin() } },
            LinearLayout.LayoutParams(0, dp(44), 1.1f).apply { leftMargin = dp(6) })
        lb.addView(row, lp(6, dp(44)))
        updateUaButton()
        return lb
    }

    private fun showLogin() {
        setMode(Mode.LOGIN)
        sessionSeenAt = 0L
        ui.removeCallbacks(verifyTick)
        verifyCard.visibility = View.GONE
        appLayer.visibility = View.GONE
        settingsView.visibility = View.GONE
        loginBar.visibility = View.VISIBLE
        webMargin(dp(132))
        note("Вход в TikTok…")
        web.settings.userAgentString = loginUa()
        web.loadUrl("https://www.tiktok.com/login")
        ui.removeCallbacks(poll); ui.postDelayed(poll, 2000)
    }

    private fun showApp() {
        appLayer.visibility = View.VISIBLE
        loginBar.visibility = View.GONE
        webMargin(0)
        tvUser.text = "@$user"
    }

    private fun loggedIn(name: String) {
        if (name != user) { items.clear(); seen.clear(); gridAdapter.notifyDataSetChanged() }
        user = name
        prefs.edit().putString("user", name).apply()
        CookieManager.getInstance().flush()
        verifyCard.visibility = View.GONE
        showApp()
        startCollect()
    }

    private fun logout() {
        if (running) { toast("Сначала остановите удаление"); return }
        AlertDialog.Builder(this).setTitle("Выйти из TikTok?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выйти") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                prefs.edit().remove("user").remove("avatar").apply()
                items.clear(); seen.clear(); gridAdapter.notifyDataSetChanged()
                avatar.setImageDrawable(null)
                showLogin()
            }.show()
    }

    // ---------- состояние ----------
    private fun deleted(): Int {
        if (System.currentTimeMillis() - prefs.getLong("last", 0) >= DAY_MS) prefs.edit().putInt("deleted", 0).apply()
        return prefs.getInt("deleted", 0) + delPending
    }

    private fun bump(n: Int) {
        delPending += n
        if (System.currentTimeMillis() - lastFlush > 700) flushDeleted()
    }

    private fun flushDeleted() {
        if (delPending == 0) return
        val base = if (System.currentTimeMillis() - prefs.getLong("last", 0) >= DAY_MS) 0 else prefs.getInt("deleted", 0)
        prefs.edit().putInt("deleted", base + delPending).putLong("last", System.currentTimeMillis()).apply()
        delPending = 0; lastFlush = System.currentTimeMillis()
    }

    // ---------- журнал отладки и скорость ----------
    private fun dlog(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logLines.add("$t $s")
        if (logLines.size > 500) logLines.removeAt(0)
    }

    private fun showLog() {
        val body = if (logLines.isEmpty()) "Журнал пока пуст" else logLines.joinToString("\n")
        val tv = TextView(this).apply {
            text = body; textSize = 10f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8)); setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("Журнал отладки").setView(sv)
            .setPositiveButton("Копировать") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DeepReposts", body))
                toast("Скопировано — вставьте в чат")
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun speedName(i: Int) = when (i) { 0 -> "максимальная, 24–48 мс"; 1 -> "быстрая, 0,1–0,3 с"; else -> "осторожная, 0,5–1,5 с" }
    private fun speedMin(): Long = when (prefs.getInt("speed", 0)) { 0 -> 24L; 1 -> 100L; else -> 500L }
    private fun speedMax(): Long = when (prefs.getInt("speed", 0)) { 0 -> 48L; 1 -> 300L; else -> 1500L }

    private fun resetText(): String {
        if (deleted() == 0) return "Лимит свободен"
        val left = prefs.getLong("last", 0) + DAY_MS - System.currentTimeMillis()
        return "Лимит сбросится через ${left / 3600000} ч ${(left / 60000) % 60} мин"
    }

    private fun saveUnchecked() { prefs.edit().putString("unchecked", unchecked.joinToString(",")).apply() }

    private fun refreshStats() {
        val d = deleted()
        var kept = 0
        for (x in items) if (x.id !in unchecked) kept++
        tvTotal.text = items.size.toString()
        tvSel.text = kept.toString()
        tvDel.text = "$d/$LIMIT"
        bar.progress = d
        btnDelete.text = if (running) "Остановить" else "Удалить без галочки"
    }

    private fun refresh() { refreshStats(); if (mode == Mode.IDLE) tvStatus.text = resetText() }

    private fun toggle(pos: Int) {
        if (running) return
        val id = items[pos - 1].id
        if (!unchecked.add(id)) unchecked.remove(id)
        saveUnchecked(); refreshStats(); gridAdapter.notifyItemChanged(pos)
    }

    private fun clearAllChecks() {
        if (running) return
        for (x in items) unchecked.add(x.id)
        saveUnchecked(); refreshStats(); gridAdapter.notifyDataSetChanged()
    }

    private fun showAvatar(url: String) {
        avatar.load(url) { transformations(CircleCropTransformation()) }
    }

    // ---------- работа в фоне ----------
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun note(text: String) {
        val t = System.currentTimeMillis()
        if (serviceOn && t - lastNote < 3000) return
        lastNote = t
        try {
            if (!serviceOn) {
                val i = Intent(this, KeepAliveService::class.java).putExtra("text", text)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                serviceOn = true
            } else {
                getSystemService(NotificationManager::class.java).notify(1, KeepAliveService.build(this, text))
            }
        } catch (e: Exception) { /* уведомление не критично */ }
    }

    private fun noteStop() {
        serviceOn = false
        try { stopService(Intent(this, KeepAliveService::class.java)) } catch (e: Exception) { }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Ограничения уже сняты"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { toast("Откройте настройки батареи телефона и разрешите DeepReposts работать без ограничений") }
    }

    // ---------- загрузка списка репостов ----------
    private fun startCollect() {
        setMode(Mode.COLLECT); armed = true; tabSeen = false
        items.clear(); seen.clear(); gridAdapter.notifyDataSetChanged()
        lastGrowth = System.currentTimeMillis()
        refreshStats(); tvStatus.text = "Открываю профиль…"
        note("Загрузка репостов…")
        web.loadUrl("https://www.tiktok.com/@$user")
        ui.postDelayed({ if (armed && mode == Mode.COLLECT) { armed = false; collectTick() } }, 15000)   // если страница не сообщила о загрузке
    }

    private fun collectTick() {
        if (mode != Mode.COLLECT) return
        web.evaluateJavascript(Scripts.INIT) {
            web.evaluateJavascript(Scripts.STEP) { r -> onStep(r) }
        }
    }

    private fun onStep(r: String?) {
        if (mode != Mode.COLLECT) return
        val o = try { JSONObject(r ?: "") } catch (e: Exception) { null }
        if (o == null || o.has("err")) { ui.postDelayed({ collectTick() }, 700); return }
        val now = System.currentTimeMillis()
        val arr = o.getJSONArray("items")
        val before = items.size
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val id = x.getString("id")
            if (items.size < LOAD_LIMIT && seen.add(id)) items.add(Item(x.getString("url"), id, x.optString("cover")))
        }
        val added = items.size - before
        val tab = o.optInt("tab") == 1
        if (tab && !tabSeen) { tabSeen = true; lastGrowth = now }
        if (added > 0) { lastGrowth = now; gridAdapter.notifyItemRangeInserted(before + 1, added) }
        val av = o.optString("av")
        if (av.isNotEmpty() && av != (prefs.getString("avatar", "") ?: "")) { prefs.edit().putString("avatar", av).apply(); showAvatar(av) }
        refreshStats()
        val more = if (o.isNull("more")) null else o.optBoolean("more")
        val idle = now - lastGrowth
        if (added == 0 && idle > 6000 && now - lastLogged > 6000) {
            lastLogged = now
            dlog("загрузка: n=" + items.size + " api=" + o.optInt("api") + " ошибок=" + o.optInt("fails") + " hasMore=" + more +
                " tab=" + tab + " http=" + o.optInt("st") + " путь=" + o.optInt("cb") + " ответ=" + o.optString("le") + " трасса=" + o.optString("tr"))
        }
        when {
            tab && ((more == false && idle > 10000) || items.size >= LOAD_LIMIT) -> finishCollect()
            !tab && o.optInt("tries") >= 12 && o.optBoolean("lo") && !sessionOk() -> {
                prefs.edit().remove("user").apply()
                toast("Сессия TikTok не найдена — войдите снова")
                showLogin()
            }
            !tab && o.optInt("tries") >= 30 -> failCollect(
                "Не нашёл вкладку «Репосты».\nАдрес: " + o.optString("url") + "\nВкладки: " + o.optString("info") + "\nЗаголовок: " + o.optString("title"))
            tab && idle > 60000 ->
                if (items.isNotEmpty()) finishCollect()
                else failCollect("Репосты не загрузились.\nОтветов TikTok: " + o.optInt("api") + ", ошибок: " + o.optInt("fails") + "\n" + o.optString("le"))
            else -> {
                tvStatus.text = if (tab) "Загружено репостов: ${items.size}" else "Ищу вкладку «Репосты»…"
                note("Загрузка репостов: ${items.size}")
                ui.postDelayed({ collectTick() }, 500)
            }
        }
    }

    private fun finishCollect() {
        dlog("загрузка завершена: ${items.size} репостов")
        setMode(Mode.IDLE); noteStop(); refreshStats()
        tvStatus.text = "Загружено репостов: ${items.size}. " + resetText()
    }

    private fun failCollect(msg: String) {
        setMode(Mode.IDLE); noteStop(); refreshStats()
        tvStatus.text = msg
    }

    // ---------- удаление ----------
    private fun onDeleteClick() {
        if (running) { stopRun("Остановлено"); return }
        if (mode == Mode.COLLECT) { toast("Репосты ещё загружаются. В настройках можно начать загрузку заново."); return }
        val left = LIMIT - deleted()
        val todo = items.filter { it.id in unchecked }
        if (left <= 0) { toast(resetText()); return }
        if (todo.isEmpty()) { toast("Нет репостов без галочки"); return }
        val n = minOf(left, todo.size)
        val extra = if (n < todo.size) "\nСегодня по лимиту можно удалить ещё $left." else ""
        AlertDialog.Builder(this).setTitle("Удалить $n репостов?")
            .setMessage("Репосты с галочкой останутся. Отменить удаление нельзя." + extra)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> startDelete(todo.take(n)) }.show()
    }

    private fun startDelete(list: List<Item>) {
        queue = list; qi = 0; fails = 0; running = true
        fastMode = false; tpl = null; fastIdx = 0; recv = 0; sentCnt = 0; okCount = 0; failCount = 0
        errRun = 0; pauses = 0; delayMul = 6.0; pauseUntil = 0L; diagCount = 0   // стартуем помедленнее и разгоняемся, пока TikTok отвечает без ошибок
        setMode(Mode.DELETE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshStats()
        dlog("удаление: " + list.size + " шт., скорость " + speedName(prefs.getInt("speed", 0)))
        note("Удаление: 0 из ${list.size}")
        nextVideo()
    }

    // --- первый репост удаляем «как человек» (через страницу видео) и подсматриваем, какой запрос шлёт сайт
    private fun nextVideo() {
        if (!running || fastMode) return
        if (qi >= queue.size) { stopRun("Готово: удалено $okCount"); return }
        armed = true; tries = 0
        tvStatus.text = "Удаляю ${qi + 1} из ${queue.size}"
        note("Удаление: ${qi + 1} из ${queue.size}")
        dlog("страница: " + queue[qi].url)
        web.loadUrl(queue[qi].url)
        val mine = qi
        ui.postDelayed({ if (running && !fastMode && qi == mine && armed) { armed = false; domFail() } }, 30000)
    }

    private fun removeTick() {
        if (!running || fastMode) return
        val mine = qi
        web.evaluateJavascript(Scripts.REMOVE) { r ->
            if (!running || fastMode || qi != mine) return@evaluateJavascript
            val res = (r ?: "").trim('"')
            when {
                res.startsWith("done") -> onDomDone()
                res.startsWith("skip") -> { dlog("уже не репост: $res"); onDeleted(true) }
                res.startsWith("timeout") -> { dlog("кнопка нажата, но сайт не подтвердил: $res"); domFail() }
                else -> {
                    tries++
                    if (tries >= 14) domFail() else ui.postDelayed({ removeTick() }, 700)
                }
            }
        }
    }

    private fun domFail() {
        if (!running || fastMode) return
        dlog("не удалось удалить: " + queue[qi].url)
        if (diagCount < 3) {
            diagCount++
            web.evaluateJavascript(Scripts.DIAG) { r ->
                dlog("DIAG " + (r ?: "").take(1800))
                onDeleted(false)
            }
        } else onDeleted(false)
    }

    private fun onDeleted(ok: Boolean) {
        if (!running || fastMode) return
        if (ok) { markDeleted(queue[qi]); okCount++; fails = 0 }
        else {
            failCount++
            if (++fails >= 3) {
                stopRun("3 ошибки подряд: не удалось нажать «Удалить репост». Откройте Настройки → Журнал отладки, скопируйте и пришлите разработчику.")
                return
            }
        }
        qi++
        refreshStats()
        ui.postDelayed({ nextVideo() }, Random.nextLong(600, 1500))
    }

    private fun onDomDone() {
        val x = queue[qi]
        web.evaluateJavascript(Scripts.TPL) { r ->
            if (!running) return@evaluateJavascript
            val o = try { JSONObject(r ?: "") } catch (e: Exception) { null }
            markDeleted(x); okCount++; fails = 0; qi++
            refreshStats()
            if (o != null && o.optBoolean("hasId") && queue.size - qi > 2) {
                tpl = o
                dlog("шаблон запроса: " + o.optString("m") + " " + o.optString("u").substringBefore("?") +
                    " тело=" + o.optString("b").take(60) + " тип=" + o.optString("ct"))
                startFast()
            } else {
                dlog("шаблон запроса не пойман (" + (o?.toString()?.take(200) ?: "нет") + ") — продолжаю медленно, через страницы")
                ui.postDelayed({ nextVideo() }, 800)
            }
        }
    }

    // --- быстрый режим: те же запросы, что шлёт сайт, но без загрузки страниц; скорость подстраивается под ответы TikTok
    private fun startFast() {
        val t = tpl ?: return
        web.evaluateJavascript(Scripts.FAST_INIT) { r ->
            if (!running) return@evaluateJavascript
            val res = r ?: ""
            if (res.contains("ok") || res.contains("ready")) {
                fastMode = true; fastIdx = qi; lastSendAt = System.currentTimeMillis()
                dlog("быстрый режим: осталось ${queue.size - qi}; " + t.optString("m"))
                note("Быстрое удаление…")
                fastSend(); fastPoll()
            } else {
                dlog("быстрый режим недоступен: $res")
                ui.postDelayed({ nextVideo() }, 800)
            }
        }
    }

    private fun fastSend() {
        if (!running || !fastMode || fastIdx >= queue.size) return
        val now = System.currentTimeMillis()
        if (now < pauseUntil) {
            tvStatus.text = "TikTok ограничил запросы — пауза ${(pauseUntil - now) / 1000} с"
            ui.postDelayed({ fastSend() }, 500); return
        }
        if (sentCnt - recv >= 12) { ui.postDelayed({ fastSend() }, 25); return }
        val x = queue[fastIdx]
        fastIdx++
        if (x.id.isNotEmpty() && x.id.all { it.isDigit() }) {
            sentCnt++; lastSendAt = now
            web.evaluateJavascript("window.__drFast('" + x.id + "')", null)
        } else { failCount++; recv++ }
        val d = (Random.nextLong(speedMin(), speedMax() + 1) * delayMul).toLong().coerceIn(speedMin(), 10000L)
        ui.postDelayed({ fastSend() }, d)
    }

    private fun fastPoll() {
        if (!running || !fastMode) return
        web.evaluateJavascript(Scripts.FAST_POLL) { r ->
            if (!running || !fastMode) return@evaluateJavascript
            val o = try { JSONObject(r ?: "") } catch (e: Exception) { null }
            if (o != null && !o.has("err")) {
                val arr = o.getJSONArray("res")
                val gone = HashSet<String>()
                var errs = 0
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    recv++
                    if (x.optInt("ok") == 1) { gone.add(x.getString("id")); okCount++; errRun = 0 } else { failCount++; errs++; errRun++ }
                }
                if (gone.isNotEmpty()) {
                    items.removeAll { it.id in gone }
                    for (id in gone) unchecked.remove(id)
                    gridAdapter.notifyDataSetChanged()
                    bump(gone.size)
                }
                if (errs > 0) {
                    dlog("ошибки: +$errs (подряд $errRun) " + o.optString("last"))
                    delayMul = minOf(delayMul * 1.7, 200.0)
                    if (errRun >= 8) {
                        pauses++; errRun = 0
                        pauseUntil = System.currentTimeMillis() + 60000
                        dlog("пауза 60 с (#$pauses)")
                        if (pauses >= 3) {
                            stopRun("TikTok ограничивает действия аккаунта. Подождите около часа и повторите. Удалено: $okCount")
                            return@evaluateJavascript
                        }
                    }
                } else if (arr.length() > 0) {
                    delayMul = maxOf(1.0, delayMul * 0.97)
                }
                refreshStats()
                if (System.currentTimeMillis() >= pauseUntil) tvStatus.text = "Удалено $okCount из ${queue.size}" + (if (failCount > 0) ", ошибок $failCount" else "")
                note("Удалено $okCount из ${queue.size}")
            }
            val stuck = fastIdx >= queue.size && System.currentTimeMillis() - lastSendAt > 20000
            if (recv >= queue.size || stuck) stopRun("Готово: удалено $okCount" + (if (failCount > 0) ", не удалось $failCount" else ""))
            else ui.postDelayed({ fastPoll() }, 120)
        }
    }

    private fun markDeleted(x: Item) {
        val idx = items.indexOf(x)
        if (idx >= 0) { items.removeAt(idx); gridAdapter.notifyItemRemoved(idx + 1) }
        unchecked.remove(x.id)
        bump(1)
    }

    private fun stopRun(msg: String) {
        running = false; armed = false; fastMode = false
        setMode(Mode.IDLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        flushDeleted(); saveUnchecked(); noteStop(); refresh()
        tvStatus.text = msg
        dlog("итог: $msg")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- обновления ----------
    private fun currentBuild(): Int = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()

    private fun versionText(): String {
        val pi = packageManager.getPackageInfo(packageName, 0)
        return "Версия ${pi.versionName} (сборка ${pi.longVersionCode})"
    }

    private fun checkUpdate() {
        tvUpd.text = "Проверяю…"
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "DeepReposts")
                c.connectTimeout = 10000; c.readTimeout = 15000
                if (c.responseCode == 404) {
                    runOnUiThread { tvUpd.text = "Релизов пока нет (или неверно указан репозиторий: $UPDATE_REPO)" }
                    return@Thread
                }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val remote = j.getString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: 0
                var apk = ""
                val a = j.getJSONArray("assets")
                for (i in 0 until a.length()) {
                    val u = a.getJSONObject(i).getString("browser_download_url")
                    if (u.endsWith(".apk")) apk = u
                }
                val cur = currentBuild()
                runOnUiThread {
                    if (remote > cur && apk.isNotEmpty()) { tvUpd.text = "Доступна сборка $remote"; askInstall(remote, cur, apk) }
                    else tvUpd.text = "У вас последняя версия"
                }
            } catch (e: Exception) {
                runOnUiThread { tvUpd.text = "Не удалось проверить обновления: ${e.message}" }
            }
        }.start()
    }

    private fun askInstall(remote: Int, cur: Int, url: String) {
        AlertDialog.Builder(this).setTitle("Вышло обновление")
            .setMessage("Доступна сборка $remote (у вас $cur). Вы хотите установить обновление?")
            .setNegativeButton("Не сейчас", null)
            .setPositiveButton("Установить") { _, _ -> downloadAndInstall(url) }.show()
    }

    private fun downloadAndInstall(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Разрешите установку из DeepReposts, затем вернитесь и повторите")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val f = File(getExternalFilesDir(null), "update.apk")
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val total = c.contentLength
                var done = 0L
                var lastPct = -1L
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = done * 100 / total
                                if (pct != lastPct) { lastPct = pct; runOnUiThread { tvUpd.text = "Скачиваю обновление… $pct%" } }
                            }
                        }
                    }
                }
                runOnUiThread { installApk(f) }
            } catch (e: Exception) {
                runOnUiThread { tvUpd.text = "Ошибка загрузки: ${e.message}" }
            }
        }.start()
    }

    private fun installApk(f: File) {
        tvUpd.text = "Открываю установщик…"
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------- интерфейс ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 0, h: Int = WRAP) = LinearLayout.LayoutParams(MATCH, h).apply { topMargin = dp(top) }
    private fun round(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    // все кнопки с непрозрачной заливкой — снег за ними не просвечивает
    private fun button(label: String, filled: Boolean) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; stateListAnimator = null
        setTextColor(if (filled) Color.WHITE else ICE)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (filled) setColor(RED) else { setColor(PANEL); setStroke(dp(1), ICE) }
        }
    }

    private fun stat(strip: LinearLayout, label: String): TextView {
        val v = TextView(this).apply { textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val l = TextView(this).apply { text = label; textSize = 12f; setTextColor(0xB3CFEFFF.toInt()); gravity = Gravity.CENTER }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(v); addView(l) }
        strip.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        return v
    }

    private fun buildMain() {
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(6), dp(3), dp(8))
            clipChildren = false
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "DeepReposts"; textSize = 24f; setTypeface(null, Typeface.BOLD); setTextColor(ICE)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gear = GearView(this).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { openSettings() } }
        top.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(top, lp(0))

        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PANEL); setStroke(dp(2), GOLD) }
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })

        tvUser = TextView(this).apply { textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(GOLD); gravity = Gravity.CENTER }
        header.addView(tvUser, lp(12))

        header.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(170)).apply { topMargin = dp(4) })   // тут виден фон с деревней

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(PANEL, 16)
            setPadding(dp(8), dp(14), dp(8), dp(12))
        }
        val strip = LinearLayout(this)
        tvTotal = stat(strip, "Репостов"); tvSel = stat(strip, "Выбрано"); tvDel = stat(strip, "Удалено за сутки")
        panel.addView(strip)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = LIMIT
            progressTintList = ColorStateList.valueOf(GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#0A1428"))
        }
        panel.addView(bar, lp(10, dp(6)))
        panel.addView(TextView(this).apply {
            text = "Загружается не более 50 000 репостов, чтобы не перегружать приложение"
            textSize = 11f; setTextColor(0x99CFEFFF.toInt()); gravity = Gravity.CENTER
        }, lp(8))
        header.addView(panel, lp(0))

        header.addView(button("Убрать все галочки", false).apply { setOnClickListener { clearAllChecks() } }, lp(12, dp(48)))
        btnDelete = button("Удалить без галочки", true).apply { setOnClickListener { onDeleteClick() } }
        header.addView(btnDelete, lp(8, dp(48)))

        tvStatus = TextView(this).apply {
            textSize = 13f; setTextColor(ICE); gravity = Gravity.CENTER
            background = round(PANEL, 12); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        header.addView(tvStatus, lp(10))

        rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (position == 0) 3 else 1
                }
            }
            adapter = gridAdapter
            setPadding(dp(13), 0, dp(13), dp(16))
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
        }
    }

    private fun buildSettings(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            setPadding(dp(16), dp(12), dp(16), dp(16)); isClickable = true
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "←"; textSize = 28f; setTextColor(ICE); setPadding(0, 0, dp(16), 0)
            setOnClickListener { closeSettings() }
        })
        top.addView(TextView(this).apply { text = "Настройки"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(ICE) })
        col.addView(top)

        col.addView(TextView(this).apply {
            text = versionText(); textSize = 15f; setTextColor(Color.WHITE)
            background = round(PANEL, 12); setPadding(dp(14), dp(12), dp(14), dp(12))
        }, lp(18))

        col.addView(button("Проверить обновления", true).apply { setOnClickListener { checkUpdate() } }, lp(12, dp(48)))
        tvUpd = TextView(this).apply { textSize = 13f; setTextColor(ICE) }
        col.addView(tvUpd, lp(8))

        btnSpeed = button("Скорость удаления: " + speedName(prefs.getInt("speed", 0)), false).apply {
            setOnClickListener {
                val next = (prefs.getInt("speed", 0) + 1) % 3
                prefs.edit().putInt("speed", next).apply()
                text = "Скорость удаления: " + speedName(next)
            }
        }
        col.addView(btnSpeed, lp(16, dp(48)))
        col.addView(button("Работа в фоне: снять ограничения батареи", false).apply { setOnClickListener { askBatteryExemption() } }, lp(8, dp(48)))
        col.addView(button("Обновить список репостов", false).apply {
            setOnClickListener {
                if (running) toast("Сначала остановите удаление")
                else { closeSettings(); startCollect() }
            }
        }, lp(8, dp(48)))
        col.addView(button("Журнал отладки", false).apply { setOnClickListener { showLog() } }, lp(8, dp(48)))
        col.addView(button("Выйти из аккаунта TikTok", false).apply { setOnClickListener { logout() } }, lp(8, dp(48)))
        return col
    }

    private fun openSettings() { settingsView.visibility = View.VISIBLE }
    private fun closeSettings() { settingsView.visibility = View.GONE }

    private inner class RepostAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                (header.parent as? ViewGroup)?.removeView(header)
                header.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                return object : RecyclerView.ViewHolder(header) {}
            }
            val cell = Cell(this@MainActivity)
            cell.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            val h = object : RecyclerView.ViewHolder(cell) {}
            cell.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p > 0) toggle(p)
            }
            return h
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) return
            val cell = h.itemView as Cell
            val item = items[position - 1]
            val on = item.id !in unchecked
            cell.badge.text = if (on) "✓" else ""
            cell.badge.setTextColor(if (on) BG else Color.WHITE)
            cell.badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (on) setColor(GOLD) else { setColor(0x66000000); setStroke(3, Color.WHITE) }
            }
            cell.scrim.visibility = if (on) View.GONE else View.VISIBLE   // затемнено = без галочки = будет удалён
            if (cell.img.tag != item.cover) {
                cell.img.tag = item.cover
                cell.img.setImageDrawable(null)
                if (item.cover.isNotEmpty()) cell.img.load(item.cover) { crossfade(false) }
            }
        }
    }
}

/**
 * ВСЁ, что зависит от устройства сайта TikTok, лежит здесь.
 * Если TikTok поменяет сайт — правится только этот блок.
 */
object Scripts {
    // имя пользователя вошедшего аккаунта
    const val USER = """(function(){
  function fromHref(h){var m=(h||'').match(/\/@([^\/?#]+)/);return m?m[1]:'';}
  try{
    var d=JSON.parse(document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__').textContent);
    var u=d['__DEFAULT_SCOPE__']['webapp.app-context'].user;
    if(u&&u.uniqueId)return u.uniqueId;
  }catch(e){}
  var sels=['[data-e2e="nav-profile"]','a[data-e2e*="profile"]','[data-e2e*="profile"] a'];
  for(var i=0;i<sels.length;i++){
    var a=document.querySelector(sels[i]);
    if(a){var n=fromHref(a.getAttribute('href'));if(n)return n;}
  }
  if(window.__drU)return window.__drU;
  if(window.__drU===undefined){
    window.__drU='';
    try{
      fetch('/passport/web/account/info/?aid=1459',{credentials:'include'}).then(function(r){return r.json();}).then(function(j){
        var x=(j&&j.data)||{};window.__drU=x.screen_name||x.username||x.unique_id||'';
      }).catch(function(){});
    }catch(e){}
  }
  return '';
})()"""

    // Есть ли на странице кнопка «Войти» (значит, в аккаунт не вошли)
    const val LOGIN_STATE = """(function(){
  if(document.querySelector('[data-e2e="top-login-button"],#header-login-button,[data-e2e="nav-login-button"],[data-e2e="login-button"]'))return 'out';
  var els=document.querySelectorAll('button,a');
  for(var i=0;i<els.length;i++){
    var t=(els[i].textContent||'').trim(),a=(els[i].getAttribute('aria-label')||'').trim();
    if(t==='Log in'||t==='Войти'||(a.length<20&&(a.toLowerCase()==='log in'||a.toLowerCase()==='войти')))return 'out';
  }
  return 'in';
})()"""

    // Один раз на странице профиля: «подслушивает» ответы, которыми сам TikTok отдаёт список репостов
    // (id, обложка, автор) — так не нужно листать страницу и читать картинки с экрана.
    const val INIT = """(function(){
  if(window.__dr)return 'already';
  var S=window.__dr={m:{},q:[],n:0,api:0,more:null,cursor:null,tpl:null,busy:false,busyAt:0,fails:0,tab:0,tabTry:0,tabAt:0,av:'',variant:0,stall:0,le:'',st:0,sc:0,tr:[],combo:0,waitUntil:0,before:0};
  var LIM=50000;
  function add(it){
    try{
      if(!it||!it.id||S.m[it.id]||S.n>=LIM)return;
      var a=it.author||{},v=it.video||{};
      var cv=v.cover||v.originCover||v.dynamicCover||'';
      S.m[it.id]=1;S.n++;
      S.q.push({id:String(it.id),url:'https://www.tiktok.com/@'+(a.uniqueId||'_')+'/video/'+it.id,cover:cv});
    }catch(e){}
  }
  function tap(url,d){
    try{
      if(!url||String(url).indexOf('repost')<0||!d||!d.itemList)return;
      S.api++;S.tpl=String(url);
      var nb=S.n;
      for(var i=0;i<d.itemList.length;i++)add(d.itemList[i]);
      S.more=!!d.hasMore;S.cursor=d.cursor;
      S.tr.push('+'+(S.n-nb)+'/'+d.itemList.length+' hm='+(d.hasMore?1:0)+' c='+d.cursor);
      if(S.tr.length>8)S.tr.shift();
    }catch(e){}
  }
  S.tap=tap;
  var oj=Response.prototype.json,ot=Response.prototype.text;
  Response.prototype.json=function(){var self=this;return oj.call(this).then(function(d){tap(self.url,d);return d;});};
  Response.prototype.text=function(){var self=this;return ot.call(this).then(function(t){
    try{if(self.url&&String(self.url).indexOf('repost')>-1)tap(self.url,JSON.parse(t));}catch(e){}
    return t;});};
  var xo=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return xo.apply(this,arguments);};
  var xs=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send=function(){
    var x=this;
    x.addEventListener('load',function(){
      try{
        var u=x.responseURL||x.__u||'';
        if(String(u).indexOf('repost')>-1&&(x.responseType===''||x.responseType==='text'))tap(u,JSON.parse(x.responseText));
      }catch(e){}
    });
    return xs.apply(this,arguments);
  };
  return 'ok';
})()"""

    // Короткий шаг, который приложение вызывает несколько раз в секунду: находит вкладку «Репосты»,
    // просит у TikTok следующие страницы списка, подталкивает прокрутку, отдаёт приложению новые репосты.
    const val STEP = """(function(){
  var S=window.__dr;if(!S)return {err:'noinit'};
  var LIM=50000;
  var rx=/repost|репост/i;
  function tabs(){return Array.prototype.slice.call(document.querySelectorAll('[role="tab"]'));}
  function findTab(){
    var e=document.querySelector('[data-e2e="repost-tab"]');
    if(e)return e;
    var t=tabs();
    for(var i=0;i<t.length;i++){
      var s=(t[i].textContent||'')+' '+(t[i].getAttribute('aria-label')||'')+' '+(t[i].getAttribute('data-e2e')||'');
      if(rx.test(s))return t[i];
    }
    var all=document.querySelectorAll('[data-e2e],[aria-label],[title],p,span,div,a,button');
    for(var j=0;j<all.length;j++){
      var x=all[j];
      var own=(x.children.length===0?(x.textContent||''):'');
      var s2=own+' '+(x.getAttribute('aria-label')||'')+' '+(x.getAttribute('title')||'')+' '+(x.getAttribute('data-e2e')||'');
      if(s2.length<40&&rx.test(s2))return x;
    }
    return null;
  }
  function scrape(){
    var ls=document.querySelectorAll('a[href*="/video/"]');
    for(var i=0;i<ls.length;i++){
      var m=ls[i].href.match(/\/video\/(\d+)/);
      if(!m||S.m[m[1]]||S.n>=LIM)continue;
      var im=ls[i].querySelector('img');
      S.m[m[1]]=1;S.n++;
      S.q.push({id:m[1],url:ls[i].href,cover:im?im.src:''});
    }
  }
  function parse(tx){try{return JSON.parse(tx);}catch(e){return null;}}
  // Ответ на наш запрос страницы списка. Ответ сайта-перехватчика (INIT) обрабатывается чуть позже — ждём.
  function done(st,tx){
    S.st=st;
    var d=parse(tx);
    if(d&&d.itemList){
      S.fails=0;
      try{S.tap(S.tpl,d);}catch(e){}
      setTimeout(function(){
        if(S.n===S.before)S.stall++;else S.stall=0;
        if(S.stall>=3)S.fails=Math.max(S.fails,4);
        S.busy=false;
      },30);
      return;
    }
    S.fails++;S.combo=(S.combo+1)%4;
    S.le=st+': '+String(tx||'').slice(0,100)+' [путь '+S.combo+']';
    S.waitUntil=Date.now()+Math.min(1500*S.fails,12000);
    S.busy=false;
  }
  // пути: 0 = XHR без подписи в адресе, 1 = fetch без подписи, 2 = XHR с подписью, 3 = fetch с подписью
  function page(){
    S.busy=true;S.busyAt=Date.now();S.before=S.n;
    var u=new URL(S.tpl);
    u.searchParams.set('cursor',S.cursor==null?'0':String(S.cursor));
    var cnt=parseInt(u.searchParams.get('count')||'0',10);
    if(cnt&&cnt<30)u.searchParams.set('count','30');
    if(S.combo<2){u.searchParams.delete('X-Bogus');u.searchParams.delete('_signature');}
    var url=u.toString();
    if(S.combo%2===0){
      var x=new XMLHttpRequest();
      x.open('GET',url,true);x.withCredentials=true;x.timeout=15000;
      x.onload=function(){done(x.status,x.responseText);};
      x.onerror=function(){done(0,'xhr error');};
      x.ontimeout=function(){done(0,'timeout');};
      x.send();
    }else{
      fetch(url,{credentials:'include'}).then(function(r){var st=r.status;return r.text().then(function(tx){done(st,tx);});}).catch(function(e){done(0,'net '+e);});
    }
  }
  if(!S.av){
    var av=document.querySelector('[data-e2e="user-avatar"] img')||document.querySelector('[data-e2e="user-avatar"]');
    if(av){var src=av.src||av.getAttribute('src')||'';if(src&&src.indexOf('data:')!==0)S.av=src;}
  }
  if(!S.tab){
    var t=findTab();
    if(t){t.click();S.tab=1;S.tabAt=Date.now();}else S.tabTry++;
  }else{
    if(S.busy&&Date.now()-S.busyAt>15000){S.busy=false;S.fails++;S.le='timeout';S.combo=(S.combo+1)%4;}
    if(S.tpl&&S.more!==false&&!S.busy&&S.fails<12&&S.n<LIM&&Date.now()>=S.waitUntil)page();
    if((S.api===0&&Date.now()-S.tabAt>12000)||S.fails>=4)scrape();
    if(Date.now()-S.sc>700){S.sc=Date.now();window.scrollTo(0,document.documentElement.scrollHeight);}
  }
  function loginVisible(){
    if(document.querySelector('[data-e2e="top-login-button"],#header-login-button,[data-e2e="nav-login-button"],[data-e2e="login-button"]'))return true;
    var els=document.querySelectorAll('button,a');
    for(var i=0;i<els.length;i++){
      var t=(els[i].textContent||'').trim(),a=(els[i].getAttribute('aria-label')||'').trim();
      if(t==='Log in'||t==='Войти'||(a.length<20&&(a.toLowerCase()==='log in'||a.toLowerCase()==='войти')))return true;
    }
    return false;
  }
  var out=S.q.splice(0,S.q.length);
  var info='';
  if(!S.tab&&S.tabTry>=25)info=tabs().map(function(e){return (e.textContent||'').trim()+'['+(e.getAttribute('data-e2e')||'')+']';}).join(' | ')||'нет';
  return {n:S.n,api:S.api,more:S.more,tab:S.tab,tries:S.tabTry,fails:S.fails,items:out,url:location.href,av:S.av,info:info,title:document.title,
    le:S.le,st:S.st,cb:S.combo,tr:S.tr.join(' ; '),lo:(!S.tab&&S.tabTry>=12)?loginVisible():false};
})()"""

    // Страница видео: подсматривает запросы сайта, нажимает кнопку репоста ОДИН раз и ждёт, пока сайт
    // подтвердит удаление ответом status_code = 0. Возвращает 'wait' | 'done' | 'skip:…' | 'timeout:…'
    const val REMOVE = """(function(){
  var vm=location.pathname.match(/\/video\/(\d+)/);
  var D=window.__drD;
  if(!D){
    D=window.__drD={vid:vm?vm[1]:'',clicked:0,shared:0,net:[],ok:0,tpl:null,desc:'',at:0,ticks:0,confirmed:0};
    var ctype=function(h){try{if(!h)return '';if(typeof h.get==='function')return h.get('content-type')||'';return h['Content-Type']||h['content-type']||'';}catch(e){return '';}};
    var bodyOf=function(b){try{if(typeof b==='string')return b;if(b&&typeof b.toString==='function'&&b instanceof URLSearchParams)return b.toString();}catch(e){}return '';};
    var judge=function(rec){
      if(rec.m==='GET'||!D.clicked)return;
      var path=rec.u.split('?')[0];
      var sure=/repost/i.test(path),maybe=/\/api\/(commit|item)\//i.test(path);
      if(rec.c===0&&(sure||maybe)){
        D.ok=1;
        D.tpl={u:rec.u,m:rec.m,b:rec.b,ct:rec.ct,hasId:!!(D.vid&&(rec.u.indexOf(D.vid)>-1||(rec.b||'').indexOf(D.vid)>-1))};
      }
    };
    var of=window.fetch;
    window.fetch=function(input,init){
      var u=(typeof input==='string')?input:((input&&input.url)||'');
      var m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();
      var p=of.apply(this,arguments);
      if(m!=='GET'&&u.indexOf('/api/')>-1){
        var rec={u:u,m:m,b:bodyOf(init&&init.body),ct:ctype(init&&init.headers),s:0,c:null};
        D.net.push(rec);if(D.net.length>40)D.net.shift();
        p.then(function(r){rec.s=r.status;return r.clone().text();}).then(function(t){
          try{var j=JSON.parse(t);rec.c=(j.status_code!==undefined)?j.status_code:((j.statusCode!==undefined)?j.statusCode:null);}catch(e){rec.c='x';}
          judge(rec);
        }).catch(function(){});
      }
      return p;
    };
    var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open=function(m,u){this.__m=String(m).toUpperCase();this.__u=String(u);return xo.apply(this,arguments);};
    XMLHttpRequest.prototype.send=function(b){
      var x=this;
      if(x.__m&&x.__m!=='GET'&&x.__u.indexOf('/api/')>-1){
        var rec={u:x.__u,m:x.__m,b:bodyOf(b),ct:'',s:0,c:null};
        D.net.push(rec);if(D.net.length>40)D.net.shift();
        x.addEventListener('load',function(){
          rec.s=x.status;
          try{var j=JSON.parse(x.responseText);rec.c=(j.status_code!==undefined)?j.status_code:((j.statusCode!==undefined)?j.statusCode:null);}catch(e){rec.c='x';}
          judge(rec);
        });
      }
      return xs.apply(this,arguments);
    };
  }
  D.ticks++;
  if(D.ok)return 'done';
  function btnOf(e){return (e.closest&&e.closest('button,[role="button"]'))||e;}
  function desc(e){return e.tagName+'['+(e.getAttribute('data-e2e')||'')+'|'+(e.getAttribute('aria-label')||'').slice(0,30)+'|'+(e.getAttribute('aria-pressed')||'')+']';}
  function findDirect(){
    var q=document.querySelectorAll('[data-e2e*="repost" i],[aria-label*="repost" i],[aria-label*="репост" i]');
    for(var i=0;i<q.length;i++){
      if(q[i].getAttribute('data-e2e')==='repost-tab')continue;
      return btnOf(q[i]);
    }
    return null;
  }
  function findMenuItem(){
    var els=document.querySelectorAll('button,[role="button"],[role="menuitem"],li,div,span');
    for(var i=0;i<els.length;i++){
      var t=(els[i].textContent||'').trim();
      if(t.length<30&&['remove repost','undo repost','repost','удалить репост','убрать репост','репост'].indexOf(t.toLowerCase())>-1)return btnOf(els[i]);
    }
    return null;
  }
  if(D.clicked){
    var dg=document.querySelector('[role="dialog"]');
    if(dg&&!D.confirmed){
      var bs=dg.querySelectorAll('button');
      for(var j=0;j<bs.length;j++){
        if(/remove|delete|confirm|yes|удалить|убрать|подтвердить|да/i.test(bs[j].textContent||'')){bs[j].click();D.confirmed=1;break;}
      }
    }
    if(Date.now()-D.at>4500)return 'timeout:'+D.desc;
    return 'wait';
  }
  var e=findDirect();
  if(e){
    if(e.getAttribute('aria-pressed')==='false')return 'skip:'+desc(e);
    D.desc=desc(e);D.clicked=1;D.at=Date.now();e.click();return 'wait';
  }
  if(!D.shared){
    var s=document.querySelector('[data-e2e="share-icon"],[data-e2e*="share"]');
    if(s){btnOf(s).click();D.shared=1;}
    return 'wait';
  }
  var mi=findMenuItem();
  if(mi){D.desc='menu:'+desc(mi);D.clicked=1;D.at=Date.now();mi.click();return 'wait';}
  return 'wait';
})()"""

    // Во время входа: «подслушивает» ответы сервера входа TikTok (код ошибки и описание) и отдаёт их приложению.
    const val LOGIN_NET = """(function(){
  var L=window.__drL;
  if(!L){
    L=window.__drL={q:[]};
    var rec=function(m,u,st,t){
      try{
        var path=String(u).split('?')[0];
        if(path.indexOf('passport')<0&&path.indexOf('login')<0&&path.indexOf('verify')<0)return;
        var d=null;try{d=JSON.parse(t);}catch(e){}
        var dd=(d&&d.data)||{};
        var code=(dd.error_code!==undefined)?dd.error_code:((d&&d.error_code!==undefined)?d.error_code:'');
        var desc=dd.description||(d&&d.description)||(d&&d.message)||'';
        L.q.push(m+' '+path.replace('https://www.tiktok.com','')+' http='+st+' код='+code+' '+String(desc).slice(0,80));
      }catch(e){}
    };
    var of=window.fetch;
    window.fetch=function(input,init){
      var u=(typeof input==='string')?input:((input&&input.url)||'');
      var m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();
      var p=of.apply(this,arguments);
      if(m!=='GET'){p.then(function(r){var st=r.status;return r.clone().text().then(function(t){rec(m,u,st,t);});}).catch(function(){});}
      return p;
    };
    var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open=function(m,u){this.__m=String(m).toUpperCase();this.__u=String(u);return xo.apply(this,arguments);};
    XMLHttpRequest.prototype.send=function(b){
      var x=this;
      x.addEventListener('load',function(){
        if(x.__m&&x.__m!=='GET'){var t='';try{t=x.responseText;}catch(e){}rec(x.__m,x.__u,x.status,t);}
      });
      return xs.apply(this,arguments);
    };
  }
  return L.q.splice(0,L.q.length);
})()"""

    // Кто вошёл: данные страницы (SSR), затем ссылка «Профиль» в меню, затем нажатие на «Профиль».
    const val WHOAMI = """(function(){
  var W=window.__drW||(window.__drW={n:0,clicked:0});
  W.n++;
  var res={name:'',how:'',url:location.pathname,n:W.n};
  try{
    var el=document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
    if(el){
      var d=JSON.parse(el.textContent);
      var sc=d['__DEFAULT_SCOPE__']||{};
      var ctx=sc['webapp.app-context']||{};
      var u=ctx.user;
      if(u&&u.uniqueId){res.name=String(u.uniqueId);res.how='данные страницы';return res;}
      var m=JSON.stringify(ctx).match(/"uniqueId":"([^"]+)"/);
      if(m){res.name=m[1];res.how='данные страницы (контекст)';return res;}
      res.how='в данных страницы нет аккаунта; ключи: '+Object.keys(sc).slice(0,10).join(',');
    }else res.how='нет данных страницы';
  }catch(e){res.how='ошибка данных: '+e;}
  var sels=['[data-e2e="nav-profile"]','a[data-e2e*="profile" i]','a[aria-label*="rofile"]','a[aria-label*="рофиль"]'];
  for(var i=0;i<sels.length;i++){
    var a=document.querySelector(sels[i]);
    if(a){
      var h=a.getAttribute('href')||((a.closest&&a.closest('a'))?a.closest('a').getAttribute('href'):'')||'';
      var mm=h.match(/\/@([^\/?#]+)/);
      if(mm){res.name=mm[1];res.how='меню '+sels[i];return res;}
    }
  }
  if(W.n>=5){
    var leaf=null,all=document.querySelectorAll('a,button,div,span,p');
    for(var j=0;j<all.length;j++){
      var x=all[j];
      if(x.children.length===0){var t=(x.textContent||'').trim();if(t==='Profile'||t==='Профиль'){leaf=x;break;}}
    }
    if(leaf&&!W.clicked){W.clicked=1;(leaf.closest('a,button,[role="button"]')||leaf).click();res.how+=' | нажал «Профиль»';}
    var pm=location.pathname.match(/^\/@([^\/?#]+)/);
    if(pm){res.name=pm[1];res.how='после нажатия «Профиль»';return res;}
  }
  return res;
})()"""

    // Что пойман для повтора: запрос, который сайт отправил при удалении (с id видео внутри)
    const val TPL = """(function(){var D=window.__drD;return D&&D.tpl?D.tpl:null;})()"""

    // Если не получилось: что на странице (data-e2e кнопок, подписи, последние запросы) — для журнала отладки
    const val DIAG = """(function(){
  var D=window.__drD||{};
  var e2=[],seen={};
  var els=document.querySelectorAll('[data-e2e]');
  for(var i=0;i<els.length&&e2.length<45;i++){var k=els[i].getAttribute('data-e2e');if(!seen[k]){seen[k]=1;e2.push(k);}}
  var ar=[];var bs=document.querySelectorAll('button,[role="button"]');
  for(var j=0;j<bs.length&&ar.length<25;j++){var a=(bs[j].getAttribute('aria-label')||(bs[j].textContent||'')).trim().slice(0,24);if(a)ar.push(a);}
  var net=(D.net||[]).slice(-6).map(function(r){return r.m+' '+r.u.split('?')[0]+' '+r.s+'/'+r.c;});
  return 'url='+location.pathname+' | нажато='+(D.desc||'нет')+' | e2e: '+e2.join(',')+' | кнопки: '+ar.join(',')+' | запросы: '+net.join(' ; ');
})()"""

    // Быстрый режим: повторяет пойманный запрос для другого id (через тот же fetch сайта, поэтому подписывается им же)
    const val FAST_INIT = """(function(){
  var D=window.__drD;if(!D||!D.tpl)return 'notpl';
  if(window.__drF)return 'ready';
  var F=window.__drF={inflight:0,res:[],last:'',sent:0,pref:'xhr'};
  function verdict(id,st,tx){
    var code=null;
    try{var j=JSON.parse(tx);code=(j.status_code!==undefined)?j.status_code:((j.statusCode!==undefined)?j.statusCode:null);}catch(e){code='x';}
    return {id:id,ok:(st===200&&code===0)?1:0,st:st,c:code,tx:tx};
  }
  window.__drFast=function(id){
    var t=D.tpl,vid=D.vid;
    var u=t.u.split(vid).join(id),b=(t.b||'').split(vid).join(id);
    var ct=t.ct||(b.charAt(0)==='{'?'application/json':'application/x-www-form-urlencoded');
    F.inflight++;F.sent++;
    function fin(r,viaFetch){
      if(!r.ok&&F.pref==='xhr'&&!viaFetch&&(r.c==='x'||r.st===0)){doFetch();return;}
      if(r.ok&&viaFetch)F.pref='fetch';
      F.res.push({id:id,ok:r.ok,st:r.st,c:r.c});
      if(!r.ok)F.last=r.st+':'+r.c+':'+String(r.tx).slice(0,100)+' ['+(viaFetch?'fetch':'xhr')+']';
      F.inflight--;
    }
    function doXhr(){
      var x=new XMLHttpRequest();
      x.open(t.m,u,true);x.withCredentials=true;x.timeout=15000;
      if(b)x.setRequestHeader('Content-Type',ct);
      x.onload=function(){fin(verdict(id,x.status,x.responseText),false);};
      x.onerror=function(){fin({id:id,ok:0,st:0,c:'e',tx:'xhr error'},false);};
      x.ontimeout=function(){fin({id:id,ok:0,st:0,c:'t',tx:'timeout'},false);};
      x.send(b||null);
    }
    function doFetch(){
      var init={method:t.m,credentials:'include'};
      if(b){init.body=b;init.headers={'Content-Type':ct};}
      fetch(u,init).then(function(r){var st=r.status;return r.text().then(function(tx){fin(verdict(id,st,tx),true);});}).catch(function(e){fin({id:id,ok:0,st:0,c:'e',tx:'net '+e},true);});
    }
    if(F.pref==='fetch')doFetch();else doXhr();
  };
  return 'ok';
})()"""

    const val FAST_POLL = """(function(){
  var F=window.__drF;if(!F)return {err:1};
  var r=F.res.splice(0,F.res.length);
  return {inflight:F.inflight,res:r,last:F.last,sent:F.sent};
})()"""
}
