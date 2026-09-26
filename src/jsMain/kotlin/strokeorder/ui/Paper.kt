package strokeorder.ui

import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import strokeorder.util.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

fun newCanvas(w: Int, h: Int): HTMLCanvasElement {
    val c = document.createElement("canvas") as HTMLCanvasElement
    c.width = max(1, w)
    c.height = max(1, h)
    return c
}

fun HTMLCanvasElement.ctx2d(): CanvasRenderingContext2D = getContext("2d") as CanvasRenderingContext2D

/** Round caps and joins (set through dynamic to avoid the DOM enum wrappers). */
fun CanvasRenderingContext2D.roundLines() {
    asDynamic().lineCap = "round"
    asDynamic().lineJoin = "round"
}

/**
 * Procedural washi: a deckled sheet with cloudy thickness, fine grain and long kozo
 * fibres. Deterministic for a given seed, so the saved PNG shows the same paper.
 */
object Paper {
    /** A small noise tile, reused for paper grain and for the texture inside ink. */
    private var grainTile: HTMLCanvasElement? = null

    fun grain(): HTMLCanvasElement = grainTile ?: run {
        val size = 96
        val c = newCanvas(size, size)
        val g = c.ctx2d()
        val img = g.createImageData(size.toDouble(), size.toDouble())
        val d = img.data.asDynamic()
        val rng = Rng(1234)
        for (i in 0 until size * size) {
            val v = rng.next()
            d[i * 4] = 255; d[i * 4 + 1] = 255; d[i * 4 + 2] = 255
            // mostly transparent, with sparse brighter specks
            d[i * 4 + 3] = if (v > 0.93) (40 + 150 * (v - 0.93) / 0.07).toInt() else (v * 22).toInt()
        }
        g.putImageData(img, 0.0, 0.0)
        grainTile = c
        c
    }

    /** Paints the sheet into [c] ([w]×[h] device pixels, [dpr] device pixels per CSS pixel). */
    fun paint(c: HTMLCanvasElement, theme: Theme, dpr: Double, seed: Int = 20260714) {
        val w = c.width.toDouble()
        val h = c.height.toDouble()
        val g = c.ctx2d()
        val rng = Rng(seed)
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.clearRect(0.0, 0.0, w, h)

        // Deckle edge: a slightly ragged outline a few pixels inside the canvas.
        val inset = 3.0 * dpr
        g.beginPath()
        val step = 3.0 * dpr
        fun ragged(): Double = inset + rng.next() * 1.8 * dpr + (if (rng.next() > 0.96) rng.next() * 1.6 * dpr else 0.0)
        var x = inset
        g.moveTo(x, ragged())
        while (x < w - inset) { x += step; g.lineTo(min(x, w - inset), ragged()) }
        var y = inset
        while (y < h - inset) { y += step; g.lineTo(w - ragged(), min(y, h - inset)) }
        x = w - inset
        while (x > inset) { x -= step; g.lineTo(max(x, inset), h - ragged()) }
        y = h - inset
        while (y > inset) { y -= step; g.lineTo(ragged(), max(y, inset)) }
        g.closePath()
        g.fillStyle = theme.paper
        g.fill()

        // Everything else stays inside the sheet.
        g.save()
        g.globalCompositeOperation = "source-atop"

        // Cloudy thickness: a tiny random image stretched smooth across the sheet.
        val mottle = if (theme.multiply) listOf(Triple(theme.fibreLight, 0.22, 7.0), Triple(theme.fibreDark, 0.05, 7.0), Triple(theme.fibreDark, 0.04, 60.0))
        else listOf(Triple(theme.fibreLight, 0.07, 7.0), Triple(theme.fibreDark, 0.18, 7.0), Triple(theme.fibreLight, 0.035, 60.0))
        for ((rgb, alpha, cell) in mottle) {
            val mw = max(4, (w / (cell * dpr)).toInt())
            val mh = max(4, (h / (cell * dpr)).toInt())
            val m = newCanvas(mw, mh)
            val mg = m.ctx2d()
            val img = mg.createImageData(mw.toDouble(), mh.toDouble())
            val d = img.data.asDynamic()
            val (r, gg, b) = rgb.split(',').map { it.trim().toInt() }
            for (i in 0 until mw * mh) {
                d[i * 4] = r; d[i * 4 + 1] = gg; d[i * 4 + 2] = b
                d[i * 4 + 3] = (rng.next() * 255 * alpha).toInt()
            }
            mg.putImageData(img, 0.0, 0.0)
            g.imageSmoothingEnabled = true
            g.drawImage(m, 0.0, 0.0, w, h)
        }

        // Fine grain.
        g.globalAlpha = if (theme.multiply) 0.55 else 0.35
        g.fillStyle = g.createPattern(grain(), "repeat")
        g.fillRect(0.0, 0.0, w, h)
        g.globalAlpha = 1.0

        // Fibres: many short, a few long; mostly pale, some dark.
        val cssArea = (w / dpr) * (h / dpr)
        val count = (cssArea / 700).toInt().coerceIn(180, 1100)
        g.roundLines()
        repeat(count) {
            val long = rng.next() > 0.9
            val len = (if (long) rng.range(40.0, 130.0) else rng.range(5.0, 34.0)) * dpr
            val sx = rng.next() * w
            val sy = rng.next() * h
            val a = rng.next() * PI * 2
            val ex = sx + cos(a) * len
            val ey = sy + sin(a) * len
            val bend = rng.range(-0.3, 0.3) * len
            val cx = (sx + ex) / 2 - sin(a) * bend
            val cy = (sy + ey) / 2 + cos(a) * bend
            val dark = rng.next() > 0.78
            val alpha = if (dark) rng.range(0.04, 0.12) else rng.range(0.16, if (long) 0.34 else 0.5)
            g.strokeStyle = "rgba(${if (dark) theme.fibreDark else theme.fibreLight},$alpha)"
            g.lineWidth = (if (long) rng.range(0.3, 0.7) else rng.range(0.35, 1.15)) * dpr
            g.beginPath()
            g.moveTo(sx, sy)
            g.quadraticCurveTo(cx, cy, ex, ey)
            g.stroke()
        }

        // Bark flecks.
        repeat((cssArea / 16000).toInt().coerceIn(6, 60)) {
            g.fillStyle = "rgba(${theme.fibreDark},${rng.range(0.12, 0.3)})"
            g.beginPath()
            g.ellipse(rng.next() * w, rng.next() * h, rng.range(0.4, 1.3) * dpr, rng.range(0.3, 0.8) * dpr, rng.next() * PI, 0.0, PI * 2)
            g.fill()
        }

        // A breath of light across the middle, falling off to the edges.
        val grad = g.createRadialGradient(w * 0.45, h * 0.4, 0.0, w * 0.5, h * 0.5, sqrt(w * w + h * h) * 0.62)
        grad.addColorStop(0.0, "rgba(${theme.fibreLight},${if (theme.multiply) 0.10 else 0.05})")
        grad.addColorStop(1.0, "rgba(${theme.fibreDark},${if (theme.multiply) 0.07 else 0.18})")
        g.fillStyle = grad
        g.fillRect(0.0, 0.0, w, h)
        g.restore()
    }
}
