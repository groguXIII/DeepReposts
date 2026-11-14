package app.deepreposts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
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
    val badge = TextView(c).apply { gravity = Gravity.CENTER; textSize = 14f }

    init {
        val d = c.resources.displayMetrics.density
        val pad = (3 * d).toInt()
        setPadding(pad, pad, pad, pad)
        val b = (26 * d).toInt()
        addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(badge, FrameLayout.LayoutParams(b, b, Gravity.END or Gravity.TOP).apply { setMargins(b / 4, b / 4, b / 4, b / 4) })
    }

    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

class MainActivity : Activity() {
    private enum class Mode { LOGIN, COLLECT, DELETE, IDLE }

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
    private lateinit var tvLoginInfo: TextView

    private var mode = Mode.LOGIN
    private var armed = false
    private var running = false
    private var serviceOn = false
    private var tabSeen = false
    private var loginPolls = 0
    private var reloadedForUser = false
    private var askedName = false
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
        appLayer.visibility = View.GONE
        root.addView(appLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        loginBar = buildLoginBar()
        root.addView(loginBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        askNotifications()
        user = prefs.getString("user", "") ?: ""
        val cached = prefs.getString("avatar", "") ?: ""
        if (cached.isNotEmpty()) showAvatar(cached)
        if (user.isNotEmpty() && sessionOk()) { showApp(); startCollect() } else showLogin()
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
            running || mode == Mode.COLLECT -> moveTaskToBack(true)   // не закрываем — работа продолжается
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
        settings.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) { pageDone() }

            // видео и картинки браузеру не нужны: так страницы грузятся в разы быстрее и не тратят трафик
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (mode == Mode.COLLECT || mode == Mode.DELETE) {
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
        webMargin(if (mode == Mode.LOGIN) dp(60) else 0)
        web.settings.blockNetworkImage = mode == Mode.COLLECT || mode == Mode.DELETE
        when {
            running -> { armed = true; tries = 0; web.loadUrl(queue[qi].url) }
            mode == Mode.COLLECT -> failCollect("Окно браузера было перезапущено системой. Откройте настройки и нажмите «Обновить список репостов».")
            mode == Mode.LOGIN -> web.loadUrl("https://www.tiktok.com/login")
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        web.settings.blockNetworkImage = m == Mode.COLLECT || m == Mode.DELETE
    }

    private fun pageDone() {
        if (!armed) return
        if (mode == Mode.COLLECT) { armed = false; ui.postDelayed({ collectTick() }, 800) }
        else if (mode == Mode.DELETE) { armed = false; ui.postDelayed({ removeTick() }, 1200) }
    }

    // ---------- вход ----------
    private fun sessionOk(): Boolean {
        val c = CookieManager.getInstance().getCookie("https://www.tiktok.com") ?: return false
        return c.contains("sessionid=") || c.contains("sid_tt=") || c.contains("sid_guard=")
    }

    private fun webMargin(px: Int) {
        web.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = px }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (mode != Mode.LOGIN) return
            CookieManager.getInstance().flush()   // чтобы вход не терялся после закрытия приложения
            if (!sessionOk()) {
                loginPolls = 0
                tvLoginInfo.text = "Войдите в TikTok на странице выше"
                ui.postDelayed(this, 2000)
                return
            }
            loginPolls++
            tvLoginInfo.text = "Вход выполнен, определяю аккаунт… ($loginPolls)"
            web.evaluateJavascript(Scripts.USER) { r ->
                val name = (r ?: "").trim('"')
                if (name.isNotEmpty() && name != "null" && mode == Mode.LOGIN) {
                    loggedIn(name)
                    return@evaluateJavascript
                }
                // если страница входа уже закрылась, а аккаунт не виден — открываем главную заново
                if (loginPolls == 8 && !reloadedForUser && !(web.url ?: "").contains("login")) {
                    reloadedForUser = true
                    web.loadUrl("https://www.tiktok.com/")
                }
                if (loginPolls >= 15 && !askedName) { askedName = true; askUsername() }
                ui.postDelayed(this, 2000)
            }
        }
    }

    /** Кнопка «Я вошёл(а)»: не ждём автоопределения. */
    private fun manualLogin() {
        if (!sessionOk()) { toast("Вход в TikTok ещё не выполнен. Войдите на странице выше."); return }
        web.evaluateJavascript(Scripts.USER) { r ->
            val name = (r ?: "").trim('"')
            if (name.isNotEmpty() && name != "null") loggedIn(name) else askUsername()
        }
    }

    private fun buildLoginBar(): LinearLayout {
        val lb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        tvLoginInfo = TextView(this).apply { textSize = 12f; setTextColor(ICE); text = "Войдите в TikTok на странице выше" }
        lb.addView(tvLoginInfo, LinearLayout.LayoutParams(0, WRAP, 1f))
        lb.addView(button("Я вошёл(а)", true).apply { textSize = 14f; setOnClickListener { manualLogin() } },
            LinearLayout.LayoutParams(WRAP, dp(44)).apply { leftMargin = dp(8) })
        return lb
    }

    private fun askUsername() {
        val et = android.widget.EditText(this).apply { hint = "например, maria_k"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Введите ваш @юзернейм TikTok")
            .setMessage("Не удалось определить аккаунт автоматически.")
            .setView(et)
            .setNegativeButton("Позже") { _, _ -> askedName = false; loginPolls = 5 }
            .setPositiveButton("Готово") { _, _ ->
                val n = et.text.toString().trim().removePrefix("@")
                if (n.isNotEmpty() && mode == Mode.LOGIN) loggedIn(n) else { askedName = false; loginPolls = 5 }
            }.show()
    }

    private fun showLogin() {
        setMode(Mode.LOGIN)
        loginPolls = 0; reloadedForUser = false; askedName = false
        appLayer.visibility = View.GONE
        settingsView.visibility = View.GONE
        loginBar.visibility = View.VISIBLE
        webMargin(dp(60))
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
        return prefs.getInt("deleted", 0)
    }

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
        when {
            tab && (more == false || items.size >= LOAD_LIMIT) -> finishCollect()
            !tab && o.optInt("tries") >= 30 -> failCollect(
                "Не нашёл вкладку «Репосты».\nАдрес: " + o.optString("url") + "\nВкладки: " + o.optString("info") + "\nЗаголовок: " + o.optString("title"))
            tab && now - lastGrowth > 30000 ->
                if (items.isNotEmpty()) finishCollect()
                else failCollect("Репосты не загрузились.\nОтветов TikTok: " + o.optInt("api") + ", ошибок: " + o.optInt("fails") + "\nАдрес: " + o.optString("url"))
            else -> {
                tvStatus.text = if (tab) "Загружено репостов: ${items.size}" else "Ищу вкладку «Репосты»…"
                note("Загрузка репостов: ${items.size}")
                ui.postDelayed({ collectTick() }, 500)
            }
        }
    }

    private fun finishCollect() {
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
        if (mode == Mode.COLLECT) { toast("Репосты ещё загружаются"); return }
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
        setMode(Mode.DELETE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshStats()
        note("Удаление: 0 из ${list.size}")
        nextVideo()
    }

    private fun nextVideo() {
        if (!running) return
        if (qi >= queue.size) { stopRun("Готово"); return }
        armed = true; tries = 0
        tvStatus.text = "Удаляю ${qi + 1} из ${queue.size}"
        note("Удаление: ${qi + 1} из ${queue.size}")
        web.loadUrl(queue[qi].url)
        val mine = qi
        ui.postDelayed({ if (running && qi == mine && armed) { armed = false; onDeleted(false) } }, 30000)   // страница так и не загрузилась
    }

    private fun removeTick() {
        if (!running) return
        val mine = qi
        web.evaluateJavascript(Scripts.REMOVE) { r ->
            if (!running || qi != mine) return@evaluateJavascript
            if ((r ?: "").contains("done")) onDeleted(true)
            else {
                tries++
                if (tries >= 14) onDeleted(false) else ui.postDelayed({ removeTick() }, 700)
            }
        }
    }

    private fun onDeleted(ok: Boolean) {
        if (!running) return
        if (ok) {
            fails = 0
            val x = queue[qi]
            val idx = items.indexOf(x)
            if (idx >= 0) { items.removeAt(idx); gridAdapter.notifyItemRemoved(idx + 1) }
            unchecked.remove(x.id)
            prefs.edit().putInt("deleted", deleted() + 1).putLong("last", System.currentTimeMillis()).apply()
            if (qi % 20 == 0) saveUnchecked()
        } else if (++fails >= 5) {
            stopRun("5 ошибок подряд: TikTok изменил страницу или не принимает запросы")
            return
        }
        qi++
        refreshStats()
        ui.postDelayed({ nextVideo() }, Random.nextLong(1200, 2500))   // небольшая пауза между удалениями
    }

    private fun stopRun(msg: String) {
        running = false; armed = false
        setMode(Mode.IDLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveUnchecked(); noteStop(); refresh()
        tvStatus.text = msg
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

        header.addView(SceneView(this), LinearLayout.LayoutParams(MATCH, WRAP).apply {
            leftMargin = -dp(16); rightMargin = -dp(16); topMargin = dp(4)
        })

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

        col.addView(button("Работа в фоне: снять ограничения батареи", false).apply { setOnClickListener { askBatteryExemption() } }, lp(16, dp(48)))
        col.addView(button("Обновить список репостов", false).apply {
            setOnClickListener {
                if (running || mode == Mode.COLLECT) toast("Приложение сейчас занято")
                else { closeSettings(); startCollect() }
            }
        }, lp(8, dp(48)))
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
            cell.img.alpha = if (on) 1f else 0.5f   // без галочки = будет удалён
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

    // Один раз на странице профиля: «подслушивает» ответы, которыми сам TikTok отдаёт список репостов
    // (id, обложка, автор) — так не нужно листать страницу и читать картинки с экрана.
    const val INIT = """(function(){
  if(window.__dr)return 'already';
  var S=window.__dr={m:{},q:[],n:0,api:0,more:null,cursor:null,tpl:null,busy:false,busyAt:0,fails:0,tab:0,tabTry:0,tabAt:0,av:''};
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
      for(var i=0;i<d.itemList.length;i++)add(d.itemList[i]);
      S.more=!!d.hasMore;S.cursor=d.cursor;
    }catch(e){}
  }
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
    // просит у TikTok следующие страницы списка, отдаёт приложению новые репосты.
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
  function page(){
    S.busy=true;S.busyAt=Date.now();
    var u=new URL(S.tpl);
    u.searchParams.set('cursor',S.cursor==null?'0':String(S.cursor));
    u.searchParams.delete('X-Bogus');u.searchParams.delete('_signature');
    fetch(u.toString(),{credentials:'include'}).then(function(r){return r.json();}).then(function(d){
      if(!d||!d.itemList)S.fails++;else S.fails=0;
      S.busy=false;
    }).catch(function(){S.fails++;S.busy=false;});
  }
  if(!S.av){
    var av=document.querySelector('[data-e2e="user-avatar"] img')||document.querySelector('[data-e2e="user-avatar"]');
    if(av){var src=av.src||av.getAttribute('src')||'';if(src&&src.indexOf('data:')!==0)S.av=src;}
  }
  if(!S.tab){
    var t=findTab();
    if(t){t.click();S.tab=1;S.tabAt=Date.now();}else S.tabTry++;
  }else{
    if(S.busy&&Date.now()-S.busyAt>15000){S.busy=false;S.fails++;}
    if(S.tpl&&S.more!==false&&!S.busy&&S.fails<3&&S.n<LIM)page();
    if((S.api===0&&Date.now()-S.tabAt>12000)||S.fails>=3){
      scrape();
      window.scrollTo(0,document.documentElement.scrollHeight);
    }
  }
  var out=S.q.splice(0,S.q.length);
  var info='';
  if(!S.tab&&S.tabTry>=25)info=tabs().map(function(e){return (e.textContent||'').trim()+'['+(e.getAttribute('data-e2e')||'')+']';}).join(' | ')||'нет';
  return {n:S.n,api:S.api,more:S.more,tab:S.tab,tries:S.tabTry,fails:S.fails,items:out,url:location.href,av:S.av,info:info,title:document.title};
})()"""

    // На странице видео: нажимает «Удалить репост» (сразу или через меню «Поделиться»)
    // и возвращает 'done', только когда эта кнопка действительно пропала.
    const val REMOVE = """(function(){
  var R=window.__drR||(window.__drR={clicked:0,shared:0});
  var re=/remove repost|undo repost|удалить репост|убрать репост|отменить репост/i;
  function find(){
    var els=document.querySelectorAll('button,[role="button"],[role="menuitem"],[aria-label],[data-e2e]');
    for(var i=0;i<els.length;i++){
      var t=(els[i].getAttribute('aria-label')||'')+' '+(els[i].textContent||'');
      if(t.length<80&&re.test(t))return els[i];
    }
    return null;
  }
  var b=find();
  if(R.clicked){
    if(!b)return 'done';
    var d=document.querySelector('[role="dialog"]');
    if(d){
      var bs=d.querySelectorAll('button');
      for(var j=0;j<bs.length;j++){
        if(/remove|delete|confirm|удалить|убрать|подтвердить/i.test(bs[j].textContent||'')){bs[j].click();break;}
      }
    }
    return 'wait';
  }
  if(b){b.click();R.clicked=1;return 'wait';}
  if(!R.shared){
    var s=document.querySelector('[data-e2e="share-icon"],[data-e2e="undefined-icon"]');
    if(s){s.click();R.shared=1;}
  }
  return 'wait';
})()"""
}
