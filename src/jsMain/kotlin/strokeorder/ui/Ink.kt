package strokeorder.ui

import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import strokeorder.ink.BrushModel
import strokeorder.ink.BrushSample
import strokeorder.ink.BrushStroke
import strokeorder.ink.Nib
import strokeorder.util.Rng
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints brush strokes. The mark is the brush's flat, angled footprint stamped along
 * the stroke (so its width follows the direction of travel, and its ends are slanted
 * where the footprint turns as the tip lands and presses), with a soft wet edge, a denser core, ink that is
 * wetter where the brush lands and drier towards the end, paper grain showing through,
 * and broken dry-brush streaks (kasure) only where the brush moved fast and ran dry.
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

    /** Strokes shorter than this (units) are dabs: no angled entry, no drying along them. */
    private const val DAB = 20.0

    private fun arcLength(samples: List<BrushSample>): Double {
        var a = 0.0
        for (i in 1 until samples.size) a += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        return a
    }

    /**
     * Adds the brush's footprint at every sample; filled once, they merge into the mark.
     * The footprint's angle turns at the start and at a stop, which slants the ends.
     */
    private fun footprints(g: CanvasRenderingContext2D, samples: List<BrushSample>, f: Frame, widthScale: Double, dx: Double, dy: Double) {
        g.beginPath()
        for (s in samples) {
            val rx = max(0.3, s.w * 0.5 * f.scale * widthScale)
            val ry = max(0.25, rx * Nib.RATIO)
            val x = f.x(s.x) - dx
            val y = f.y(s.y) - dy
            g.moveTo(x + rx * cos(s.angle), y + rx * sin(s.angle))
            g.ellipse(x, y, rx, ry, s.angle, 0.0, 2 * PI)
        }
    }

    /** The stroke while it is still being drawn: one wet fill, no ending yet. */
    fun paintLive(g: CanvasRenderingContext2D, samples: List<BrushSample>, f: Frame, theme: Theme, dpr: Double) {
        if (samples.isEmpty()) return
        g.save()
        g.fillStyle = theme.ink
        g.shadowColor = "rgba(${theme.inkRgb},0.5)"
        g.shadowBlur = 1.4 * dpr
        g.globalAlpha = 0.94
        footprints(g, samples, f, 1.0, 0.0, 0.0)
        g.fill()
        g.restore()
    }

    /**
     * A finished stroke. [progress] (0..1) paints only the first part, for animation.
     * [alpha] fades the whole stroke (the pale model uses this). [texture] adds the
     * paper grain, the drying of the ink and the dry-brush streaks.
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
        val length = arcLength(samples)
        val dab = arcLength(all) < DAB

        // Bounding box in device pixels, padded for the soft edge and the entry and stop.
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var maxW = 0.0
        for (s in samples) {
            minX = min(minX, f.x(s.x)); maxX = max(maxX, f.x(s.x))
            minY = min(minY, f.y(s.y)); maxY = max(maxY, f.y(s.y))
            maxW = max(maxW, s.w)
        }
        val pad = maxW * f.scale * 0.9 + 6 * dpr
        val bx = floor(minX - pad); val by = floor(minY - pad)
        val bw = ceil(maxX - minX + 2 * pad).toInt(); val bh = ceil(maxY - minY + 2 * pad).toInt()
        val c = scratch(bw, bh)
        val g = c.ctx2d()
        g.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        g.globalCompositeOperation = "source-over"
        g.globalAlpha = 1.0
        g.clearRect(0.0, 0.0, bw.toDouble(), bh.toDouble())

        // Body with a soft, bled edge (nijimi). Light ink on dark paper bleeds less visibly:
        // a strong halo there reads as a glow, not as ink soaking in.
        val light = colorRgb.split(',').sumOf { it.trim().toIntOrNull() ?: 0 } > 450
        g.fillStyle = color
        g.shadowColor = "rgba($colorRgb,${if (light) 0.22 else 0.45})"
        g.shadowBlur = 1.4 * dpr
        g.globalAlpha = 0.92
        footprints(g, samples, f, 1.0, bx, by)
        g.fill()
        g.shadowBlur = 0.0
        g.shadowColor = "transparent"
        // Denser core where the brush carries the most ink.
        g.globalAlpha = 0.42
        footprints(g, samples, f, 0.58, bx, by)
        g.fill()
        g.globalAlpha = 1.0

        if (texture) {
            val start = samples.first()
            val sx = f.x(start.x) - bx
            val sy = f.y(start.y) - by
            if (!dab && length > 0.0) {
                // Wet where the brush lands, drier towards the end: the ink thins with distance
                // from the start (measured along the page, which follows the stroke closely enough).
                var reach = 0.0
                for (s in samples) reach = max(reach, hypot(f.x(s.x) - bx - sx, f.y(s.y) - by - sy))
                if (reach > 1.0) {
                    g.globalCompositeOperation = "destination-out"
                    val fade = g.createRadialGradient(sx, sy, reach * 0.35, sx, sy, reach)
                    fade.addColorStop(0.0, "rgba(0,0,0,0)")
                    fade.addColorStop(1.0, "rgba(0,0,0,0.17)")
                    g.fillStyle = fade
                    g.fillRect(0.0, 0.0, bw.toDouble(), bh.toDouble())
                    // A little pooled ink where the brush first pressed.
                    g.globalCompositeOperation = "source-atop"
                    val r = start.w * f.scale * 1.3
                    val pool = g.createRadialGradient(sx, sy, 0.0, sx, sy, r)
                    pool.addColorStop(0.0, "rgba($colorRgb,0.35)")
                    pool.addColorStop(1.0, "rgba($colorRgb,0)")
                    g.fillStyle = pool
                    g.fillRect(sx - r, sy - r, 2 * r, 2 * r)
                }
            }
            // Paper grain showing through the ink.
            g.globalCompositeOperation = "source-atop"
            g.globalAlpha = 0.16
            g.fillStyle = g.createPattern(Paper.grain(), "repeat")
            g.fillRect(0.0, 0.0, bw.toDouble(), bh.toDouble())
            g.globalAlpha = 1.0
            // Dry-brush streaks where the brush ran fast and dry.
            g.globalCompositeOperation = "destination-out"
            kasure(g, stroke, samples, f, bx, by, dpr)
        }
        g.globalCompositeOperation = "source-over"

        target.save()
        target.globalAlpha = alpha
        target.drawImage(c, 0.0, 0.0, bw.toDouble(), bh.toDouble(), bx, by, bw.toDouble(), bh.toDouble())
        target.restore()
    }

    /** Smooth 1-D value noise in 0..1: random values on whole numbers, eased in between. */
    private fun noise(x: Double, seed: Int): Double {
        val i = floor(x)
        val t = x - i
        val u = t * t * (3 - 2 * t)
        return lattice(i.toInt(), seed) * (1 - u) + lattice(i.toInt() + 1, seed) * u
    }

    private fun lattice(i: Int, seed: Int): Double {
        var h = i * 374761393 + seed * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535.0
    }

    /**
     * Dry brush (kasure): the bristles part and leave white streaks along the stroke.
     * Each streak is a bristle "lane" at its own place across the mark; it opens only
     * where the brush is dry enough for that bristle (fast stretches, the end of the
     * ink, a sweep's tail), and breaks up irregularly along its length, so the streaks
     * are broken and uneven rather than parallel lines.
     */
    private fun kasure(g: CanvasRenderingContext2D, stroke: BrushStroke, samples: List<BrushSample>, f: Frame, bx: Double, by: Double, dpr: Double) {
        val n = samples.size
        if (n < 8 || stroke.dryness < 0.1) return
        val rng = Rng(stroke.seed)
        val arc = DoubleArray(n)
        for (i in 1 until n) arc[i] = arc[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        // Direction and mark width at each sample, once.
        val nx = DoubleArray(n); val ny = DoubleArray(n); val half = DoubleArray(n)
        for (i in 0 until n) {
            val d = BrushModel.direction(samples, i)
            nx[i] = -d.y; ny[i] = d.x
            half[i] = 0.5 * Nib.markWidth(samples[i].w, d.x, d.y, samples[i].angle)
        }
        g.asDynamic().lineCap = "round"
        g.asDynamic().lineJoin = "round"
        val lanes = 8 + (stroke.dryness * 9).toInt()
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ws = ArrayList<Double>()
        repeat(lanes) { lane ->
            val across = rng.range(-0.44, 0.44)
            val wander = rng.range(0.02, 0.07)
            val needs = rng.range(0.12, 0.55) // how dry the brush must be before this bristle parts
            val thin = rng.range(0.035, 0.11)
            val period = rng.range(2.2, 6.5)
            val phase = rng.next() * 997
            val strength = rng.range(0.5, 1.0)
            val seed = rng.nextInt()
            var runDry = 0.0
            fun flush() {
                if (xs.size >= 3) draw(g, xs, ys, ws, strength * (0.55 + 0.45 * min(1.0, runDry / xs.size / 0.6)), f, dpr)
                xs.clear(); ys.clear(); ws.clear(); runDry = 0.0
            }
            for (i in 0 until n) {
                val s = samples[i]
                val open = s.dry > needs && noise((arc[i] + phase) / period, seed) < (s.dry - needs) * 2.2
                if (!open) { flush(); continue }
                val off = (across + wander * sin(arc[i] / (period * 1.7) + phase)) * 2 * half[i]
                xs.add(f.x(s.x + nx[i] * off) - bx)
                ys.add(f.y(s.y + ny[i] * off) - by)
                ws.add(2 * half[i] * thin * (0.7 + 0.6 * s.dry))
                runDry += s.dry
            }
            flush()
        }
    }

    /** One streak: thin where it opens and closes, full through its middle. */
    private fun draw(g: CanvasRenderingContext2D, xs: List<Double>, ys: List<Double>, ws: List<Double>, alpha: Double, f: Frame, dpr: Double) {
        val m = xs.size
        g.strokeStyle = "rgba(0,0,0,${alpha.coerceIn(0.0, 1.0)})"
        val cut = max(1, m / 5)
        val parts = listOf(0 to cut, cut to m - 1 - cut, m - 1 - cut to m - 1)
        for ((k, part) in parts.withIndex()) {
            val (a, b) = part
            if (b <= a) continue
            var w = 0.0
            for (i in a..b) w += ws[i]
            w /= (b - a + 1)
            g.lineWidth = max(0.5 * dpr, w * f.scale * (if (k == 1) 1.0 else 0.55))
            g.beginPath()
            g.moveTo(xs[a], ys[a])
            for (i in a + 1..b) g.lineTo(xs[i], ys[i])
            g.stroke()
        }
    }
}
