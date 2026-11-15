package app.deepreposts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.random.Random

private fun c(s: String) = Color.parseColor(s)

/** Ночное небо: градиент, звёзды, луна. Неподвижный фон всего экрана. */
class BgView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = ArrayList<FloatArray>()
    private val village = Village(d)

    init {
        val r = Random(7)
        repeat(70) { stars.add(floatArrayOf(r.nextFloat(), r.nextFloat() * 0.6f, 0.6f + r.nextFloat() * 1.3f, 0.35f + r.nextFloat() * 0.55f)) }
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(c("#060C20"), c("#0F2147"), c("#162C55")), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = null
        for (s in stars) {
            p.color = Color.argb((s[3] * 255).toInt(), 255, 255, 255)
            cv.drawCircle(s[0] * w, s[1] * h, s[2] * d, p)
        }
        // луна с мягким ореолом
        val mx = w * 0.86f
        val my = 92 * d
        p.shader = RadialGradient(mx, my, 46 * d, c("#44F6EFD0"), c("#00F6EFD0"), Shader.TileMode.CLAMP)
        cv.drawCircle(mx, my, 46 * d, p)
        p.shader = null
        p.color = c("#F6EFD0")
        cv.drawCircle(mx, my, 15 * d, p)
        p.color = c("#E3DAB4")
        cv.drawCircle(mx - 4 * d, my - 3 * d, 3 * d, p)
        cv.drawCircle(mx + 5 * d, my + 4 * d, 2 * d, p)
        // деревня стоит на месте — прокручивается только интерфейс поверх неё
        cv.save()
        cv.translate(0f, 172 * d)
        village.draw(cv, w)
        cv.restore()
    }
}

/** Шестерёнка настроек (рисуется кодом, без картинок). */
class GearView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c("#CFEFFF") }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 2f
        p.style = Paint.Style.FILL
        for (i in 0 until 8) {
            cv.save()
            cv.rotate(i * 45f, cx, cy)
            cv.drawRoundRect(cx - r * 0.15f, cy - r, cx + r * 0.15f, cy - r * 0.55f, r * 0.06f, r * 0.06f, p)
            cv.restore()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.3f
        cv.drawCircle(cx, cy, r * 0.56f, p)
    }
}

/** Деревенька на снегу: три дома (два с включённым светом, один тёмный), ёлки, снеговики. */
class Village(private val d: Float) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun fill(color: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = color }
    private fun line(color: Int, wd: Float) { p.shader = null; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = wd * d }
    private fun rc(cv: Canvas, l: Float, t: Float, r: Float, b: Float) = cv.drawRect(l * d, t * d, r * d, b * d, p)
    private fun oval(cv: Canvas, l: Float, t: Float, r: Float, b: Float) = cv.drawOval(l * d, t * d, r * d, b * d, p)
    private fun circ(cv: Canvas, x: Float, y: Float, r: Float) = cv.drawCircle(x * d, y * d, r * d, p)
    private fun ln(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = cv.drawLine(x1 * d, y1 * d, x2 * d, y2 * d, p)
    private fun tri(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x1 * d, y1 * d); path.lineTo(x2 * d, y2 * d); path.lineTo(x3 * d, y3 * d); path.close()
        cv.drawPath(path, p)
    }

    fun draw(cv: Canvas, w: Float) {
        val wd = w / d
        // дальние холмы
        fill(c("#1A3563"))
        path.reset()
        path.moveTo(0f, 92 * d)
        path.quadTo(w * 0.22f, 66 * d, w * 0.5f, 88 * d)
        path.quadTo(w * 0.78f, 108 * d, w, 78 * d)
        path.lineTo(w, 170 * d); path.lineTo(0f, 170 * d); path.close()
        cv.drawPath(path, p)
        // снежное поле (внизу плавно растворяется в фон)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 104 * d, 0f, 170 * d, intArrayOf(c("#5B83BA"), c("#3D6199"), c("#00162C55")), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(0f, 120 * d)
        path.quadTo(w * 0.25f, 104 * d, w * 0.5f, 116 * d)
        path.quadTo(w * 0.78f, 128 * d, w, 110 * d)
        path.lineTo(w, 170 * d); path.lineTo(0f, 170 * d); path.close()
        cv.drawPath(path, p)
        p.shader = null

        tree(cv, wd * 0.05f, 150f, 74f)
        house(cv, wd * 0.52f, 140f, 54f, 34f, false)
        tree(cv, wd * 0.38f, 146f, 60f)
        tree(cv, wd * 0.68f, 144f, 64f)
        house(cv, wd * 0.19f, 148f, 66f, 40f, true)
        house(cv, wd * 0.83f, 150f, 62f, 38f, true)
        tree(cv, wd * 0.96f, 154f, 62f)
        snowman(cv, wd * 0.34f, 160f, 1f)
        snowman(cv, wd * 0.69f, 158f, 0.85f)
    }

    private fun house(cv: Canvas, cx: Float, base: Float, w: Float, h: Float, lit: Boolean) {
        fill(c("#33000000")); oval(cv, cx - w * 0.7f, base - 3f, cx + w * 0.7f, base + 5f)
        // труба
        fill(c("#4A2F1E")); rc(cv, cx + w * 0.16f, base - h - 26f, cx + w * 0.16f + 8f, base - h - 6f)
        fill(c("#E9F2FF")); rc(cv, cx + w * 0.16f - 1f, base - h - 29f, cx + w * 0.16f + 9f, base - h - 25f)
        // стены из брёвен
        fill(if (lit) c("#7A5230") else c("#5C4128")); rc(cv, cx - w / 2, base - h, cx + w / 2, base)
        line(c("#40000000"), 1f)
        var y = base - h + 6f
        while (y < base - 2f) { ln(cv, cx - w / 2, y, cx + w / 2, y); y += 6f }
        // крыша
        fill(if (lit) c("#8E3B30") else c("#6E2E28"))
        tri(cv, cx - w / 2 - 7f, base - h, cx, base - h - 26f, cx + w / 2 + 7f, base - h)
        fill(c("#EAF3FF"))
        tri(cv, cx - (w / 2 + 7f) * 0.5f, base - h - 13f, cx, base - h - 27f, cx + (w / 2 + 7f) * 0.5f, base - h - 13f)
        oval(cv, cx - w / 2 - 9f, base - h - 2f, cx - w / 2 + 6f, base - h + 3f)
        oval(cv, cx + w / 2 - 6f, base - h - 2f, cx + w / 2 + 9f, base - h + 3f)
        // дверь
        fill(c("#35200F")); rc(cv, cx - w * 0.3f, base - h * 0.55f, cx - w * 0.1f, base)
        // окно
        val wx = cx + w * 0.14f
        val wy = base - h * 0.74f
        val s = 11f
        if (lit) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(wx * d, (wy + s / 2) * d, 28 * d, c("#88FFD36B"), c("#00FFD36B"), Shader.TileMode.CLAMP)
            cv.drawCircle(wx * d, (wy + s / 2) * d, 28 * d, p)
            p.shader = null
            fill(c("#33FFD36B")); oval(cv, wx - 16f, base - 2f, wx + 24f, base + 5f)
            fill(c("#FFD36B"))
        } else {
            fill(c("#1B2740"))
        }
        rc(cv, wx - s / 2, wy, wx + s / 2, wy + s)
        line(c("#3A2414"), 1.2f)
        ln(cv, wx, wy, wx, wy + s); ln(cv, wx - s / 2, wy + s / 2, wx + s / 2, wy + s / 2)
        // сугроб у стены
        fill(c("#DCE8F8")); oval(cv, cx - w * 0.6f, base - 3f, cx + w * 0.6f, base + 4f)
    }

    private fun tree(cv: Canvas, cx: Float, base: Float, h: Float) {
        fill(c("#33000000")); oval(cv, cx - h * 0.3f, base - 2f, cx + h * 0.3f, base + 4f)
        fill(c("#4A2F1E")); rc(cv, cx - 2.5f, base - 8f, cx + 2.5f, base)
        val bys = floatArrayOf(base - 6f, base - h * 0.34f, base - h * 0.6f)
        val ays = floatArrayOf(base - h * 0.58f, base - h * 0.82f, base - h)
        val hws = floatArrayOf(h * 0.30f, h * 0.25f, h * 0.18f)
        val cols = arrayOf("#1D5443", "#236550", "#2B7459")
        for (i in 0 until 3) {
            fill(c(cols[i]))
            tri(cv, cx - hws[i], bys[i], cx, ays[i], cx + hws[i], bys[i])
            fill(c("#E9F2FF"))
            val my = ays[i] + (bys[i] - ays[i]) * 0.42f
            tri(cv, cx - hws[i] * 0.42f, my, cx, ays[i] - 0.5f, cx + hws[i] * 0.42f, my)
        }
        fill(c("#DCE8F8")); oval(cv, cx - h * 0.2f, base - 2f, cx + h * 0.2f, base + 3f)
    }

    private fun snowman(cv: Canvas, cx: Float, base: Float, k: Float) {
        val r1 = 11f * k
        val r2 = 8.5f * k
        val r3 = 6.5f * k
        val y1 = base - r1
        val y2 = y1 - r1 * 0.75f - r2 * 0.75f
        val y3 = y2 - r2 * 0.75f - r3 * 0.8f
        fill(c("#33000000")); oval(cv, cx - r1 * 1.3f, base - 2f, cx + r1 * 1.3f, base + 4f)
        // руки-веточки
        line(c("#5A3B22"), 1.5f)
        ln(cv, cx - r2, y2, cx - r2 - 10f * k, y2 - 7f * k)
        ln(cv, cx + r2, y2, cx + r2 + 10f * k, y2 - 7f * k)
        // шары
        fill(c("#F4F8FF")); circ(cv, cx, y1, r1); circ(cv, cx, y2, r2); circ(cv, cx, y3, r3)
        line(c("#BFD3EE"), 1f)
        p.style = Paint.Style.STROKE
        circ(cv, cx, y1, r1); circ(cv, cx, y2, r2); circ(cv, cx, y3, r3)
        // шарф
        fill(c("#D8344A")); rc(cv, cx - r3 * 1.05f, y3 + r3 * 0.7f, cx + r3 * 1.05f, y3 + r3 * 0.7f + 3.2f * k)
        rc(cv, cx + r3 * 0.3f, y3 + r3 * 0.7f, cx + r3 * 0.3f + 3f * k, y3 + r3 * 0.7f + 10f * k)
        // шляпа
        fill(c("#1C1F2B")); rc(cv, cx - r3 * 1.05f, y3 - r3 * 0.85f, cx + r3 * 1.05f, y3 - r3 * 0.6f)
        rc(cv, cx - r3 * 0.68f, y3 - r3 * 0.85f - 8f * k, cx + r3 * 0.68f, y3 - r3 * 0.8f)
        // лицо и пуговицы
        fill(c("#1C1F2B")); circ(cv, cx - r3 * 0.35f, y3 - r3 * 0.2f, 0.9f * k); circ(cv, cx + r3 * 0.35f, y3 - r3 * 0.2f, 0.9f * k)
        circ(cv, cx, y2 + r2 * 0.15f, 1f * k); circ(cv, cx, y2 + r2 * 0.55f, 1f * k)
        fill(c("#FF8A2B")); tri(cv, cx, y3 + r3 * 0.05f, cx + 7f * k, y3 + r3 * 0.25f, cx, y3 + r3 * 0.4f)
    }
}
