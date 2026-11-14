package app.deepreposts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.random.Random

/** Падающий снег поверх всего экрана; касания пропускает вниз. */
class SnowView(c: Context) : View(c) {
    private class Flake(var x: Float, var y: Float, val r: Float, val v: Float, val drift: Float)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val flakes = ArrayList<Flake>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        flakes.clear()
        val d = resources.displayMetrics.density
        repeat(70) {
            flakes += Flake(
                Random.nextFloat() * w, Random.nextFloat() * h,
                (1.5f + Random.nextFloat() * 3f) * d,
                (0.6f + Random.nextFloat() * 1.6f) * d,
                (Random.nextFloat() - 0.5f) * 0.6f * d
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (f in flakes) {
            f.y += f.v; f.x += f.drift
            if (f.y > height + f.r) { f.y = -f.r; f.x = Random.nextFloat() * width }
            if (f.x < -f.r) f.x = width + f.r else if (f.x > width + f.r) f.x = -f.r
            canvas.drawCircle(f.x, f.y, f.r, paint)
        }
        postInvalidateOnAnimation()
    }
}
