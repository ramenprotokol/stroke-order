package strokeorder.ui

import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import strokeorder.ink.BrushSample
import strokeorder.ink.BrushStroke
import strokeorder.ink.Ending
import strokeorder.util.Rng
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Paints brush strokes. A stroke is the union of discs along its samples (so the width
 * can change continuously), with a soft wet edge, a darker core, paper grain showing
 * through, and dry-brush streaks (kasure) eaten out of the tail.
 */
object Ink {
    private var scratch: HTMLCanvasElement? = null

    private fun scratch(w: Int, h: Int): HTMLCanvasElement {
        val c = scratch ?: newCanvas(w, h).also { scratch = it }
        if (c.width < w || c.height < h) {
            c.width = max(c.width, w)
            c.height = max(c.height, h)
        }
        return c
    }

    private fun discs(g: CanvasRenderingContext2D, samples: List<BrushSample>, f: Frame, widthScale: Double, dx: Double, dy: Double) {
        g.beginPath()
        val first = samples.firstOrNull()
        if (first != null && samples.size > 3) {
            // The entry (kihitsu): the brush tip lands pointing to the upper left, which
            // leaves a slanted head rather than a round cap.
            val r = first.w * 0.5 * f.scale * widthScale
            val x = f.x(first.x) - dx
            val y = f.y(first.y) - dy
            g.moveTo(x + r * 1.18, y)
            g.ellipse(x - r * 0.1, y - r * 0.1, r * 1.2, r * 0.82, PI / 4, 0.0, 2 * PI)
        }
        for (s in samples) {
            val r = max(0.35, s.w * 0.5 * f.scale * widthScale)
            val x = f.x(s.x) - dx
            val y = f.y(s.y) - dy
            g.moveTo(x + r, y)
            g.arc(x, y, r, 0.0, 2 * PI)
        }
    }

    /** The stroke while it is still being drawn: one wet fill, no ending yet. */
    fun paintLive(g: CanvasRenderingContext2D, samples: List<BrushSample>, f: Frame, theme: Theme, dpr: Double) {
        if (samples.isEmpty()) return
        g.save()
        g.fillStyle = theme.ink
        g.shadowColor = "rgba(${theme.inkRgb},0.55)"
        g.shadowBlur = 1.8 * dpr
        discs(g, samples, f, 1.0, 0.0, 0.0)
        g.fill()
        g.restore()
    }

    /**
     * A finished stroke. [progress] (0..1) paints only the first part, for animation.
     * [alpha] fades the whole stroke (the pale model uses this).
     */
    fun paint(
        target: CanvasRenderingContext2D,
        stroke: BrushStroke,
        f: Frame,
        color: String,
        colorRgb: String,
        dpr: Double,
        alpha: Double = 1.0,
        progress: Double = 1.0,
        texture: Boolean = true,
    ) {
        val all = stroke.samples
        if (all.isEmpty()) return
        val count = if (progress >= 1.0) all.size else max(1, (all.size * progress).toInt())
        val samples = if (count == all.size) all else all.subList(0, count)

        // Bounding box in device pixels, padded for the soft edge.
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var maxW = 0.0
        for (s in samples) {
            minX = min(minX, f.x(s.x)); maxX = max(maxX, f.x(s.x))
            minY = min(minY, f.y(s.y)); maxY = max(maxY, f.y(s.y))
            maxW = max(maxW, s.w)
        }
        val pad = maxW * f.scale * 0.6 + 6 * dpr
        val bx = floor(minX - pad); val by = floor(minY - pad)
        val bw = ceil(maxX - minX + 2 * pad).toInt(); val bh = ceil(maxY - minY + 2 * pad).toInt()
        val c = scratch(bw, bh)
        val g = c.ctx2d()
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.globalCompositeOperation = "source-over"
        g.globalAlpha = 1.0
        g.clearRect(0.0, 0.0, bw.toDouble(), bh.toDouble())

        // Body with a soft, bled edge (nijimi).
        g.fillStyle = color
        g.shadowColor = "rgba($colorRgb,0.5)"
        g.shadowBlur = 1.6 * dpr
        g.globalAlpha = 0.9
        discs(g, samples, f, 1.0, bx, by)
        g.fill()
        g.shadowBlur = 0.0
        g.shadowColor = "transparent"
        // Denser core where the brush carries the most ink.
        g.globalAlpha = 0.5
        discs(g, samples, f, 0.62, bx, by)
        g.fill()
        g.globalAlpha = 1.0

        if (texture) {
            // Paper grain showing through the ink.
            g.globalCompositeOperation = "source-atop"
            g.globalAlpha = 0.16
            g.fillStyle = g.createPattern(Paper.grain(), "repeat")
            g.fillRect(0.0, 0.0, bw.toDouble(), bh.toDouble())
            g.globalAlpha = 1.0
            // Dry-brush streaks in the tail.
            g.globalCompositeOperation = "destination-out"
            kasure(g, stroke, samples, f, bx, by, dpr)
        }
        g.globalCompositeOperation = "source-over"

        target.save()
        target.globalAlpha = alpha
        target.drawImage(c, 0.0, 0.0, bw.toDouble(), bh.toDouble(), bx, by, bw.toDouble(), bh.toDouble())
        target.restore()
    }

    /** Streaks where the brush ran dry: lines eaten out along the tail, following the stroke. */
    private fun kasure(g: CanvasRenderingContext2D, stroke: BrushStroke, samples: List<BrushSample>, f: Frame, bx: Double, by: Double, dpr: Double) {
        val n = stroke.samples.size
        if (n < 12) return
        val rng = Rng(stroke.seed)
        val sweep = stroke.ending == Ending.SWEEP
        val dry = stroke.dryness
        val tailFrom = (n * (if (sweep) 0.5 - 0.25 * dry else 0.72)).toInt()
        val streaks = if (sweep) (5 + dry * 11).toInt() else if (dry > 0.12) 3 else 0
        g.roundLines()
        repeat(streaks) {
            val offset = rng.range(-0.44, 0.44)
            val start = tailFrom + (rng.next() * (n - tailFrom) * (if (sweep) 0.55 else 0.5)).toInt()
            val thickness = rng.range(0.04, if (sweep) 0.13 else 0.07)
            val strength = rng.range(0.35, if (sweep) 0.95 else 0.5)
            var i = start
            while (i < min(n - 1, samples.size - 1)) {
                val a = stroke.samples[i]; val b = stroke.samples[i + 1]
                val dx = b.x - a.x; val dy = b.y - a.y
                val len = hypot(dx, dy)
                if (len > 1e-9) {
                    // perpendicular offset, proportional to the local brush width
                    val nx = -dy / len; val ny = dx / len
                    val u = (i - start).toDouble() / max(1, n - start)
                    g.strokeStyle = "rgba(0,0,0,${strength * (0.55 + 0.45 * u)})"
                    g.lineWidth = max(0.6 * dpr, a.w * f.scale * thickness * (0.7 + 0.8 * u))
                    g.beginPath()
                    g.moveTo(f.x(a.x + nx * a.w * offset) - bx, f.y(a.y + ny * a.w * offset) - by)
                    g.lineTo(f.x(b.x + nx * b.w * offset) - bx, f.y(b.y + ny * b.w * offset) - by)
                    g.stroke()
                }
                // dry brushes skip: small gaps appear as the tail dries out
                i += if (rng.next() < 0.04 * dry) 3 else 1
            }
        }
    }
}
