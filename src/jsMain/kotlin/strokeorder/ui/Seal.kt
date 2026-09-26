package strokeorder.ui

import org.w3c.dom.HTMLCanvasElement
import strokeorder.engine.Geometry
import strokeorder.model.Kanji
import strokeorder.util.Rng
import kotlin.math.PI
import kotlin.math.max

/**
 * The vermilion seal: a carved square (shirobun style, white character on red) reading
 * 正 ("correct"), drawn from KanjiVG's own strokes for 正 with an uneven stamp texture.
 */
object Seal {
    fun paint(c: HTMLCanvasElement, sizePx: Int, theme: Theme, glyph: Kanji?, seed: Int = 7) {
        c.width = max(8, sizePx)
        c.height = max(8, sizePx)
        val s = c.width.toDouble()
        val g = c.ctx2d()
        val rng = Rng(seed)
        g.clearRect(0.0, 0.0, s, s)

        // Slightly irregular square body.
        val m = s * 0.04
        g.beginPath()
        val steps = 18
        fun wob() = rng.range(-0.012, 0.012) * s
        for (i in 0..steps) g.lineTo(m + (s - 2 * m) * i / steps, m + wob())
        for (i in 0..steps) g.lineTo(s - m + wob(), m + (s - 2 * m) * i / steps)
        for (i in steps downTo 0) g.lineTo(m + (s - 2 * m) * i / steps, s - m + wob())
        for (i in steps downTo 0) g.lineTo(m + wob(), m + (s - 2 * m) * i / steps)
        g.closePath()
        g.fillStyle = theme.seal
        g.fill()

        g.save()
        g.globalCompositeOperation = "destination-out"
        g.roundLines()

        // Carve the character.
        if (glyph != null) {
            val inner = s * 0.66
            val off = (s - inner) / 2
            val k = inner / 109.0
            // KanjiVG glyphs sit inside roughly 10..100; centre that area.
            g.lineWidth = s * 0.075
            g.strokeStyle = "rgba(0,0,0,1)"
            for (stroke in glyph.strokes) {
                val pts = Geometry.resample(stroke.points, 24)
                g.beginPath()
                pts.forEachIndexed { i, p ->
                    val x = off + (p.x - 54.5) * k * 1.12 + inner / 2
                    val y = off + (p.y - 54.5) * k * 1.12 + inner / 2
                    if (i == 0) g.moveTo(x, y) else g.lineTo(x, y)
                }
                g.stroke()
            }
        }

        // Uneven ink transfer: specks and pale patches, denser near the rim.
        repeat(110) {
            val edge = rng.next() < 0.55
            val x = if (edge && rng.next() < 0.5) (if (rng.next() < 0.5) m + rng.next() * s * 0.08 else s - m - rng.next() * s * 0.08) else rng.next() * s
            val y = if (edge && rng.next() < 0.5) (if (rng.next() < 0.5) m + rng.next() * s * 0.08 else s - m - rng.next() * s * 0.08) else rng.next() * s
            g.fillStyle = "rgba(0,0,0,${rng.range(0.25, 0.9)})"
            g.beginPath()
            g.arc(x, y, rng.range(0.004, 0.014) * s, 0.0, 2 * PI)
            g.fill()
        }
        repeat(5) {
            g.fillStyle = "rgba(0,0,0,${rng.range(0.06, 0.16)})"
            g.beginPath()
            g.ellipse(rng.next() * s, rng.next() * s, rng.range(0.08, 0.2) * s, rng.range(0.04, 0.1) * s, rng.next() * PI, 0.0, 2 * PI)
            g.fill()
        }
        g.restore()
    }
}
