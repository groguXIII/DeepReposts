package app.deepreposts

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import coil.load
import org.json.JSONArray
import kotlin.random.Random

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val LIMIT = 2500                       // репостов в сутки
const val DAY_MS = 24L * 60 * 60 * 1000      // окно лимита считается от последнего удаления
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
        val d = (26 * c.resources.displayMetrics.density).toInt()
        addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(badge, FrameLayout.LayoutParams(d, d, Gravity.END or Gravity.TOP).apply { setMargins(d / 4, d / 4, d / 4, d / 4) })
    }
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

class MainActivity : Activity() {
    private enum class Mode { LOGIN, COLLECT, DELETE, IDLE }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var main: LinearLayout
    private lateinit var tvUser: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvSel: TextView
    private lateinit var tvDel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnDelete: Button

    private var mode = Mode.LOGIN
    private var armed = false
    private var running = false
    private var user = ""
    private var qi = 0
    private var fails = 0
    private var queue: List<Item> = emptyList()
    private val items = ArrayList<Item>()
    private val checked = HashSet<String>()
    private val bridge = Bridge()

    private val gridAdapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val cell = cv as? Cell ?: Cell(this@MainActivity)
            val item = items[p]
            val on = item.id in checked
            cell.badge.text = if (on) "✓" else ""
            cell.badge.setTextColor(if (on) BG else Color.WHITE)
            cell.badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (on) setColor(GOLD) else { setColor(0x66000000); setStroke(3, Color.WHITE) }
            }
            cell.img.alpha = if (on) 1f else 0.55f   // без галочки = будет удалён
            if (cell.img.tag != item.cover) {
                cell.img.tag = item.cover
                if (item.cover.isNotEmpty()) cell.img.load(item.cover)
            }
            return cell
        }
    }

    // ---------- жизненный цикл ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("dr", MODE_PRIVATE)
        checked.addAll(prefs.getStringSet("checked", emptySet()) ?: emptySet())

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // десктопная версия TikTok: на ней стабильнее вкладка «Репосты»
            settings.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            addJavascriptInterface(bridge, "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String?) {
                    if (!armed) return
                    val js = if (mode == Mode.COLLECT) Scripts.COLLECT else if (mode == Mode.DELETE) Scripts.REMOVE else return
                    armed = false
                    ui.postDelayed({ web.evaluateJavascript(js, null) }, 1800)
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        main = buildMain()
        main.visibility = View.GONE
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))   // виден при входе, потом закрыт main и работает «под ним»
        root.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(SnowView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        user = prefs.getString("user", "") ?: ""
        val hasSession = CookieManager.getInstance().getCookie("https://www.tiktok.com")?.contains("sessionid=") == true
        if (user.isNotEmpty() && hasSession) { main.visibility = View.VISIBLE; startCollect() } else showLogin()
    }

    override fun onResume() { super.onResume(); if (::main.isInitialized && mode == Mode.IDLE) refresh() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (mode == Mode.LOGIN && web.canGoBack()) web.goBack() else super.onBackPressed() }

    // ---------- вход ----------
    private val poll = object : Runnable {
        override fun run() {
            if (mode != Mode.LOGIN) return
            val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com") ?: ""
            if (cookies.contains("sessionid=")) {
                web.evaluateJavascript(Scripts.USER) { r ->
                    val name = (r ?: "").trim('"')
                    if (name.isNotEmpty() && name != "null" && mode == Mode.LOGIN) loggedIn(name)
                    else ui.postDelayed(this, 2000)
                }
            } else ui.postDelayed(this, 2000)
        }
    }

    private fun showLogin() {
        mode = Mode.LOGIN
        main.visibility = View.GONE
        web.loadUrl("https://www.tiktok.com/login")
        ui.removeCallbacks(poll); ui.postDelayed(poll, 2000)
    }

    private fun loggedIn(name: String) {
        user = name
        prefs.edit().putString("user", name).apply()
        main.visibility = View.VISIBLE
        startCollect()
    }

    private fun askLogout() {
        if (running) return
        AlertDialog.Builder(this).setTitle("Выйти из TikTok?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выйти") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                prefs.edit().remove("user").apply()
                items.clear(); showLogin()
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

    private fun save() { prefs.edit().putStringSet("checked", HashSet(checked)).apply() }

    private fun refresh() {
        val d = deleted()
        tvUser.text = "@$user"
        tvTotal.text = items.size.toString()
        tvSel.text = items.count { it.id in checked }.toString()
        tvDel.text = "$d/$LIMIT"
        bar.progress = d
        btnDelete.text = if (running) "Остановить" else "Удалить без галочки"
        if (mode == Mode.IDLE) tvStatus.text = resetText()
        gridAdapter.notifyDataSetChanged()
    }

    private fun toggle(id: String) {
        if (running) return
        if (!checked.add(id)) checked.remove(id)
        save(); refresh()
    }

    // ---------- загрузка списка репостов ----------
    private fun startCollect() {
        mode = Mode.COLLECT; armed = true; items.clear()
        refresh(); tvStatus.text = "Загружаю репосты…"
        web.loadUrl("https://www.tiktok.com/@$user")
    }

    // ---------- удаление ----------
    private fun onDeleteClick() {
        if (running) { stopRun("Остановлено"); return }
        if (mode == Mode.COLLECT) { toast("Репосты ещё загружаются"); return }
        val left = LIMIT - deleted()
        val todo = items.filter { it.id !in checked }
        if (left <= 0) { toast(resetText()); return }
        if (todo.isEmpty()) { toast("Нет репостов без галочки"); return }
        val n = minOf(left, todo.size)
        AlertDialog.Builder(this).setTitle("Удалить $n репостов?")
            .setMessage("Репосты с галочкой останутся. Отменить удаление нельзя.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> startDelete(todo.take(n)) }.show()
    }

    private fun startDelete(list: List<Item>) {
        queue = list; qi = 0; fails = 0; running = true; mode = Mode.DELETE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // WebView не должен засыпать
        refresh(); nextVideo()
    }

    private fun nextVideo() {
        if (!running) return
        if (qi >= queue.size) { stopRun("Готово"); return }
        armed = true
        tvStatus.text = "Удаляю ${qi + 1} из ${queue.size}"
        web.loadUrl(queue[qi].url)
        val mine = qi
        ui.postDelayed({ if (running && qi == mine) bridge.onDeleted(false) }, 25000)   // страховка от зависания
    }

    private fun stopRun(msg: String) {
        running = false; mode = Mode.IDLE; armed = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh(); tvStatus.text = msg
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- мост JS -> Kotlin ----------
    inner class Bridge {
        @JavascriptInterface fun onProgress(n: Int) { runOnUiThread { tvStatus.text = "Загружено репостов: $n" } }

        @JavascriptInterface fun onItems(json: String) {
            runOnUiThread {
                val a = JSONArray(json)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    items += Item(o.getString("url"), o.getString("id"), o.optString("cover"))
                }
                mode = Mode.IDLE; refresh()
            }
        }

        @JavascriptInterface fun onError(msg: String) { runOnUiThread { mode = Mode.IDLE; refresh(); tvStatus.text = msg } }

        @JavascriptInterface fun onDeleted(ok: Boolean) {
            runOnUiThread {
                if (!running) return@runOnUiThread
                if (ok) {
                    fails = 0
                    items.remove(queue[qi])
                    prefs.edit().putInt("deleted", deleted() + 1).putLong("last", System.currentTimeMillis()).apply()
                } else if (++fails >= 5) {
                    stopRun("5 ошибок подряд: TikTok изменил страницу или не принимает запросы"); return@runOnUiThread
                }
                qi++
                refresh()
                ui.postDelayed({ nextVideo() }, Random.nextLong(1500, 3000))   // небольшая пауза между удалениями
            }
        }
    }

    // ---------- интерфейс ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 0, h: Int = WRAP) = LinearLayout.LayoutParams(MATCH, h).apply { topMargin = dp(top) }
    private fun round(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    private fun button(label: String, filled: Boolean) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; stateListAnimator = null
        setTextColor(if (filled) Color.WHITE else ICE)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (filled) setColor(RED) else setStroke(dp(1), ICE)
        }
    }

    private fun stat(strip: LinearLayout, label: String): TextView {
        val v = TextView(this).apply { textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val l = TextView(this).apply { text = label; textSize = 12f; setTextColor(0xB3CFEFFF.toInt()); gravity = Gravity.CENTER }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(v); addView(l) }
        strip.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        return v
    }

    private fun buildMain(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "DeepReposts ❄"; textSize = 24f; setTypeface(null, Typeface.BOLD); setTextColor(ICE)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        tvUser = TextView(this).apply { textSize = 14f; setTextColor(GOLD); setOnClickListener { askLogout() } }
        head.addView(tvUser)
        col.addView(head)

        val strip = LinearLayout(this).apply { setPadding(0, dp(14), 0, dp(14)); background = round(PANEL, 16) }
        tvTotal = stat(strip, "Репостов"); tvSel = stat(strip, "Выбрано"); tvDel = stat(strip, "Удалено за сутки")
        col.addView(strip, lp(14))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = LIMIT
            progressTintList = ColorStateList.valueOf(GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(PANEL)
        }
        col.addView(bar, lp(8, dp(6)))

        val clear = button("Убрать все галочки", false).apply {
            setOnClickListener { if (!running) { checked.clear(); save(); refresh() } }
        }
        btnDelete = button("Удалить без галочки", true).apply { setOnClickListener { onDeleteClick() } }
        col.addView(clear, lp(12, dp(48)))
        col.addView(btnDelete, lp(8, dp(48)))

        tvStatus = TextView(this).apply { textSize = 13f; setTextColor(0xB3CFEFFF.toInt()); gravity = Gravity.CENTER }
        col.addView(tvStatus, lp(8))

        val grid = GridView(this).apply {
            numColumns = 3; horizontalSpacing = dp(6); verticalSpacing = dp(6)
            selector = ColorDrawable(0)
            adapter = gridAdapter
            setOnItemClickListener { _, _, p, _ -> toggle(items[p].id) }
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10) })
        return col
    }
}

/**
 * ВСЁ, что зависит от вёрстки TikTok, лежит здесь. Если TikTok поменяет сайт — правится только этот блок.
 */
object Scripts {
    // имя пользователя вошедшего аккаунта
    const val USER = """(function(){
  try{
    var d=JSON.parse(document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__').textContent);
    var u=d['__DEFAULT_SCOPE__']['webapp.app-context'].user;
    if(u&&u.uniqueId)return u.uniqueId;
  }catch(e){}
  var a=document.querySelector('[data-e2e="nav-profile"]');
  if(a){var m=(a.getAttribute('href')||'').match(/@([^\/?]+)/);if(m)return m[1];}
  return '';
})()"""

    // открывает вкладку «Репосты», прокручивает до конца, собирает ссылки и превью
    const val COLLECT = """(async function(){
  var sleep=function(ms){return new Promise(function(r){setTimeout(r,ms);});};
  var rx=/repost|репост/i;
  function tabs(){return Array.prototype.slice.call(document.querySelectorAll('[role="tab"]'));}
  function find(){
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
  var tab=null;
  for(var n=0;n<20&&!tab;n++){tab=find();if(!tab)await sleep(1000);}
  if(!tab){
    var info=tabs().map(function(e){return (e.textContent||'').trim()+'['+(e.getAttribute('data-e2e')||'')+']';}).join(' | ');
    Android.onError('Не нашёл вкладку «Репосты».\nАдрес: '+location.href+'\nВкладки: '+(info||'нет')+'\nЗаголовок: '+document.title);
    return;
  }
  tab.click();
  await sleep(2500);
  var seen={},list=[],still=0,last=-1;
  while(still<6){
    var links=document.querySelectorAll('a[href*="/video/"]');
    for(var k=0;k<links.length;k++){
      var m=links[k].href.match(/\/video\/(\d+)/);
      if(!m||seen[m[1]])continue;
      seen[m[1]]=1;
      var img=links[k].querySelector('img');
      list.push({id:m[1],url:links[k].href,cover:img?img.src:''});
    }
    Android.onProgress(list.length);
    still=(list.length===last)?still+1:0;
    last=list.length;
    window.scrollTo(0,document.body.scrollHeight);
    await sleep(1300);
  }
  Android.onItems(JSON.stringify(list));
})();"""

    // на странице видео находит «Удалить репост» (напрямую или через меню «Поделиться») и нажимает
    const val REMOVE = """(async function(){
  var sleep=function(ms){return new Promise(function(r){setTimeout(r,ms);});};
  var re=/remove repost|undo repost|удалить репост|убрать репост|отменить репост/i;
  function find(){
    var els=document.querySelectorAll('button,[role="button"],[role="menuitem"],[aria-label],[data-e2e]');
    for(var i=0;i<els.length;i++){
      var t=(els[i].getAttribute('aria-label')||'')+' '+(els[i].textContent||'');
      if(t.length<80&&re.test(t))return els[i];
    }
    return null;
  }
  var btn=null;
  for(var n=0;n<10&&!btn;n++){btn=find();if(!btn)await sleep(600);}
  if(!btn){
    var share=document.querySelector('[data-e2e="share-icon"],[data-e2e="undefined-icon"]');
    if(share){share.click();await sleep(900);btn=find();}
  }
  if(!btn){Android.onDeleted(false);return;}
  btn.click();
  await sleep(900);
  Android.onDeleted(true);
})();"""
}
