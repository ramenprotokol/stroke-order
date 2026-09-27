package strokeorder.ui

import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import strokeorder.engine.Geometry
import strokeorder.engine.Point
import strokeorder.ink.BrushModel
import strokeorder.ink.BrushStroke
import strokeorder.model.Kanji
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Show me": the reference strokes written in vermilion teacher's ink (shuzumi), one
 * after another, each numbered where it starts. With reduced motion the whole
 * character appears at once, numbered, with an arrowhead showing each stroke's direction.
 */
class Demo(private val sheet: Sheet, private val onFinish: () -> Unit) {
    private var raf = 0
    var running = false
        private set
    /** True while strokes are on the demo layer (animated or static). */
    var visible = false
        private set

    fun play(k: Kanji) {
        stop()
        val strokes = k.strokes.mapIndexed { i, s ->
            BrushModel.finish(BrushModel.fromReference(s.points, s.type), 500 + i, ending = BrushModel.endingFor(s.type))
        }
        visible = true
        if (prefersReducedMotion()) {
            drawStatic(k, strokes)
            onFinish()
            return
        }
        val durations = k.strokes.map { (it.length * 8.5 + 240).coerceIn(360.0, 1150.0) }
        val gap = 170.0
        val starts = DoubleArray(durations.size)
        for (i in 1 until durations.size) starts[i] = starts[i - 1] + durations[i - 1] + gap
        val total = starts.last() + durations.last()
        running = true
        val t0 = window.performance.now()
        // Finished strokes are painted once, with their texture, onto a layer of their own;
        // each frame only the stroke being written is painted afresh.
        val done = newCanvas(sheet.demo.width, sheet.demo.height)
        var finished = 0
        fun frame(now: Double) {
            if (!running) return
            val t = now - t0
            val g = sheet.demo.ctx2d()
            sheet.clearDemo()
            while (finished < strokes.size && t >= starts[finished] + durations[finished]) {
                Ink.paint(done.ctx2d(), strokes[finished], sheet.frame, sheet.theme.demo, rgbOf(sheet.theme.demo), sheet.dpr, alpha = 0.88)
                finished++
            }
            g.drawImage(done, 0.0, 0.0)
            if (finished < strokes.size && t >= starts[finished]) {
                val p = ((t - starts[finished]) / durations[finished]).coerceIn(0.0, 1.0)
                // ease so the brush enters slowly and leaves quickly
                Ink.paint(g, strokes[finished], sheet.frame, sheet.theme.demo, rgbOf(sheet.theme.demo), sheet.dpr, alpha = 0.88, progress = p * p * (3 - 2 * p), texture = false)
            }
            for (i in strokes.indices) if (t >= starts[i]) number(g, k, i)
            if (t < total) {
                raf = window.requestAnimationFrame(::frame)
            } else {
                running = false
                onFinish()
            }
        }
        raf = window.requestAnimationFrame(::frame)
    }

    fun stop() {
        if (running) window.cancelAnimationFrame(raf)
        running = false
    }

    /** Stops and wipes the demo layer. Cutting a running demo short counts as finishing it. */
    fun clear() {
        val wasRunning = running
        stop()
        visible = false
        sheet.clearDemo()
        if (wasRunning) onFinish()
    }

    private fun drawStatic(k: Kanji, strokes: List<BrushStroke>) {
        val g = sheet.demo.ctx2d()
        sheet.clearDemo()
        strokes.forEachIndexed { i, s ->
            Ink.paint(g, s, sheet.frame, sheet.theme.demo, rgbOf(sheet.theme.demo), sheet.dpr, alpha = 0.8)
            arrowhead(g, k.strokes[i].points)
        }
        k.strokes.indices.forEach { number(g, k, it) }
    }

    private fun number(g: CanvasRenderingContext2D, k: Kanji, i: Int) {
        val f = sheet.frame
        val stroke = k.strokes[i]
        val at = stroke.label ?: stroke.points.first().let { Point(it.x - 6, it.y - 2) }
        val size = 7.4 * f.scale
        g.save()
        g.font = "500 ${size}px 'SO Serif JP', 'Noto Serif JP', serif"
        g.asDynamic().textAlign = "left"
        g.asDynamic().textBaseline = "alphabetic"
        g.lineWidth = 3.2 * sheet.dpr
        g.strokeStyle = sheet.theme.paper
        g.roundLines()
        val label = (i + 1).toString()
        g.strokeText(label, f.x(at.x), f.y(at.y))
        g.fillStyle = sheet.theme.demo
        g.fillText(label, f.x(at.x), f.y(at.y))
        g.restore()
    }

    private fun arrowhead(g: CanvasRenderingContext2D, pts: List<Point>) {
        val f = sheet.frame
        val r = Geometry.resample(pts, 16)
        val end = r.last()
        val before = r[r.size - 4]
        val a = atan2(end.y - before.y, end.x - before.x)
        val len = 4.2
        g.save()
        g.strokeStyle = sheet.theme.demo
        g.lineWidth = 1.3 * f.scale
        g.roundLines()
        g.beginPath()
        for (side in listOf(-1.0, 1.0)) {
            val b = a + PI * 0.82 * side
            g.moveTo(f.x(end.x), f.y(end.y))
            g.lineTo(f.x(end.x + cos(b) * len), f.y(end.y + sin(b) * len))
        }
        g.stroke()
        g.restore()
    }
}

/** "r,g,b" from "#rrggbb". */
fun rgbOf(hex: String): String {
    val h = hex.removePrefix("#")
    if (h.length != 6) return "0,0,0"
    return listOf(0, 2, 4).joinToString(",") { h.substring(it, it + 2).toInt(16).toString() }
}
