package strokeorder.ink

import strokeorder.engine.Geometry
import strokeorder.engine.Point
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One pointer sample in the KanjiVG frame: position, time in ms, pen pressure if known. */
data class InputPoint(val x: Double, val y: Double, val t: Double, val pressure: Double? = null)

/** A point on the rendered brush stroke and the brush's width (diameter) there, in frame units. */
data class BrushSample(val x: Double, val y: Double, val w: Double)

/** How a stroke ends: a held stop (tome) or a flicked sweep that tapers out (harai). */
enum class Ending { STOP, SWEEP }

/**
 * A finished brush stroke ready to paint: its outline samples, how it ends, how dry the
 * brush was at the tail (0 wet … 1 very dry) and a seed for the dry-brush streaks.
 */
class BrushStroke(val samples: List<BrushSample>, val ending: Ending, val dryness: Double, val seed: Int)

/**
 * Turns pointer input into a brush stroke: faster movement makes a thinner line, pen
 * pressure (when there is any) makes it fuller, the brush presses in at the start, and
 * the end either stops round or sweeps out to a point depending on the final speed.
 */
object BrushModel {
    /** Nominal brush width in frame units (the character box is 109 wide). */
    const val BASE_WIDTH = 6.6

    /** Input beyond this many points is ignored (a stroke that long is not a stroke). */
    const val MAX_INPUT = 3000

    /** Hard cap on rendered samples. */
    const val MAX_SAMPLES = 12000

    /** Speed (units per ms) at which the width has dropped by roughly a third. */
    private const val SPEED_SCALE = 0.22

    /** Final speed above which the stroke counts as a flick (harai). */
    const val SWEEP_SPEED = 0.26

    /** Width factor for a given speed: slow = full and wet, fast = thin. */
    fun speedFactor(speed: Double): Double = 0.42 + 0.78 * exp(-max(0.0, speed) / SPEED_SCALE)

    private fun pressureFactor(p: Double?): Double = if (p == null) 1.0 else (0.55 + 0.9 * p.coerceIn(0.0, 1.0))

    /** Samples for the stroke while it is still being drawn (no ending yet). */
    fun live(input: List<InputPoint>): List<BrushSample> = shape(input, ending = null).first

    /** The finished stroke once the pen lifts. */
    fun finish(input: List<InputPoint>, seed: Int): BrushStroke {
        val ending = endingOf(input)
        val (samples, endSpeed) = shape(input, ending)
        val length = Geometry.pathLength(samples.map { Point(it.x, it.y) })
        val dryness = when (ending) {
            Ending.SWEEP -> (0.25 + 0.9 * (endSpeed - SWEEP_SPEED) + length / 260.0).coerceIn(0.2, 0.9)
            Ending.STOP -> (0.05 + length / 400.0).coerceIn(0.05, 0.3)
        }
        return BrushStroke(samples, ending, dryness, seed)
    }

    /** Average speed over roughly the last 45 ms of input. */
    fun endSpeed(input: List<InputPoint>): Double {
        if (input.size < 2) return 0.0
        val last = input.last()
        var i = input.lastIndex
        var dist = 0.0
        while (i > 0 && last.t - input[i - 1].t <= 45.0) {
            dist += hypot(input[i].x - input[i - 1].x, input[i].y - input[i - 1].y)
            i--
        }
        if (i == input.lastIndex) {
            // Only one gap inside the window: use the last segment.
            val a = input[input.lastIndex - 1]
            return hypot(last.x - a.x, last.y - a.y) / max(1.0, last.t - a.t)
        }
        return dist / max(1.0, last.t - input[i].t)
    }

    fun endingOf(input: List<InputPoint>): Ending = if (endSpeed(input) >= SWEEP_SPEED) Ending.SWEEP else Ending.STOP

    private fun shape(rawInput: List<InputPoint>, ending: Ending?): Pair<List<BrushSample>, Double> {
        val input = dedupe(if (rawInput.size > MAX_INPUT) rawInput.subList(0, MAX_INPUT) else rawInput)
        if (input.isEmpty()) return emptyList<BrushSample>() to 0.0
        if (input.size == 1) {
            val p = input[0]
            return listOf(BrushSample(p.x, p.y, BASE_WIDTH * 0.9 * pressureFactor(p.pressure))) to 0.0
        }
        // Width per input point from speed and pressure, smoothed so it breathes rather than jitters.
        val widths = DoubleArray(input.size)
        var speed = 0.0
        var w = BASE_WIDTH * speedFactor(0.0)
        for (i in input.indices) {
            if (i > 0) {
                val a = input[i - 1]; val b = input[i]
                val v = hypot(b.x - a.x, b.y - a.y) / max(1.0, b.t - a.t)
                speed = if (i == 1) v else speed + 0.3 * (v - speed)
            }
            val target = BASE_WIDTH * speedFactor(speed) * pressureFactor(input[i].pressure)
            w = if (i == 0) target else w + 0.35 * (target - w)
            widths[i] = w
        }
        val endSpeed = endSpeed(input)

        // Densify along a Catmull-Rom spline through the input points.
        val out = ArrayList<BrushSample>()
        for (i in 0 until input.size - 1) {
            val p0 = input[max(0, i - 1)]; val p1 = input[i]; val p2 = input[i + 1]; val p3 = input[min(input.lastIndex, i + 2)]
            val seg = hypot(p2.x - p1.x, p2.y - p1.y)
            val step = min(0.5, max(0.12, min(widths[i], widths[i + 1]) * 0.3))
            val n = max(1, kotlin.math.ceil(seg / step).toInt())
            for (s in 0 until n) {
                if (out.size >= MAX_SAMPLES) break
                val t = s.toDouble() / n
                out.add(BrushSample(catmull(p0.x, p1.x, p2.x, p3.x, t), catmull(p0.y, p1.y, p2.y, p3.y, t), widths[i] + (widths[i + 1] - widths[i]) * t))
            }
        }
        val last = input.last()
        out.add(BrushSample(last.x, last.y, widths.last()))

        // Walk the arc length to apply the entry press and the ending.
        val arc = DoubleArray(out.size)
        for (i in 1 until out.size) arc[i] = arc[i - 1] + hypot(out[i].x - out[i - 1].x, out[i].y - out[i - 1].y)
        val total = arc.last()
        val press = min(3.2, total * 0.25)
        val tail = when (ending) {
            Ending.SWEEP -> min(total * 0.38, 26.0)
            Ending.STOP -> min(total * 0.2, 5.0)
            null -> 0.0
        }
        val shaped = out.mapIndexed { i, s ->
            val a = arc[i]
            var m = 1.0
            if (press > 0 && a < press * 2) {
                // Press in: start a little narrow, swell just past the entry, settle.
                m *= if (a < press) 0.78 + 0.32 * (a / press) else 1.1 - 0.1 * ((a - press) / press)
            }
            if (ending != null && total > 0) {
                val f = a / total
                if (total < 24.0 && ending == Ending.STOP) {
                    // A dot (ten) is a teardrop: it lands light and presses down at the end.
                    m *= 0.62 + 0.5 * f
                } else if (total > 30.0) {
                    // The brush lifts a little through the middle of a long stroke.
                    m *= 1 - 0.1 * sin(f * PI)
                }
            }
            val fromEnd = total - a
            if (ending != null && tail > 0 && fromEnd < tail) {
                val u = 1 - fromEnd / tail // 0 at tail start … 1 at the very end
                m *= when (ending) {
                    Ending.SWEEP -> 1 - 0.9 * u * u * (3 - 2 * u) // smooth taper to a point
                    Ending.STOP -> 1 + 0.12 * sin(u * PI) // a slight swell where the brush rests
                }
            }
            BrushSample(s.x, s.y, max(0.25, s.w * m))
        }
        return shaped to endSpeed
    }

    private fun dedupe(input: List<InputPoint>): List<InputPoint> {
        if (input.size < 2) return input
        val out = ArrayList<InputPoint>(input.size)
        out.add(input[0])
        for (i in 1 until input.size) {
            val p = input[i]; val q = out.last()
            if (hypot(p.x - q.x, p.y - q.y) >= 0.25 || i == input.lastIndex) out.add(p)
        }
        if (out.size >= 2 && out[out.lastIndex].let { l -> hypot(l.x - out[out.lastIndex - 1].x, l.y - out[out.lastIndex - 1].y) } < 1e-9) {
            out.removeAt(out.lastIndex)
        }
        return out
    }

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t; val t3 = t2 * t
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    /**
     * Synthesises plausible brush input for a reference stroke (for the model and the
     * "show me" demo): slow at the entry, quicker through the middle, and either slowing
     * to a stop or accelerating into a sweep.
     */
    fun fromReference(points: List<Point>, type: String): List<InputPoint> {
        val sweep = type.firstOrNull()?.let { it in "㇒㇏㇀㇓㇝㇛㇜㇢" } ?: false
        val n = max(8, (Geometry.pathLength(points) / 1.5).toInt())
        val pts = Geometry.resample(points, n)
        var t = 0.0
        return pts.mapIndexed { i, p ->
            if (i > 0) {
                val u = i.toDouble() / (n - 1)
                val speed = when {
                    u < 0.12 -> 0.06 + 0.9 * u
                    sweep -> 0.16 + 0.5 * u * u
                    u > 0.85 -> 0.17 - 0.8 * (u - 0.85)
                    else -> 0.17
                }
                t += pts[i].dist(pts[i - 1]) / max(0.04, speed)
            }
            InputPoint(p.x, p.y, t)
        }
    }
}
