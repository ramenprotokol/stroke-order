package strokeorder.ink

import strokeorder.engine.Geometry
import strokeorder.engine.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One pointer sample in the KanjiVG frame: position, time in ms, pen pressure if known. */
data class InputPoint(val x: Double, val y: Double, val t: Double, val pressure: Double? = null)

/**
 * A point on the rendered brush stroke: where the brush is, its size there (the long
 * axis of its footprint, in frame units), how dry it is (0 wet … 1 dry, which is where
 * the bristles split and leave white streaks) and the angle of its footprint.
 */
data class BrushSample(val x: Double, val y: Double, val w: Double, val dry: Double = 0.0, val angle: Double = Nib.ANGLE)

/**
 * How a stroke ends: a held stop (tome) pressed down at an angle, a sweep (harai) that
 * tapers to a sharp point, or a flicked hook (hane) that tapers quickly.
 */
enum class Ending { STOP, SWEEP, HOOK }

/** A finished brush stroke ready to paint, with a seed for its dry-brush streaks. */
class BrushStroke(val samples: List<BrushSample>, val ending: Ending, val seed: Int) {
    /** How dry the brush ran at its driest (0 … 1). */
    val dryness: Double = samples.maxOfOrNull { it.dry } ?: 0.0
}

/**
 * The brush's footprint: a flat, oval tip held at an angle, its long axis rising to the
 * right. Dragged along a stroke, it leaves a mark whose width depends on the direction
 * of travel: across the tip (verticals, the right-falling sweep) it is broad; along the
 * tip (horizontals a little, the left-falling sweep most) it is narrower. This is what
 * gives brush writing its thick-and-thin rhythm even at a steady speed.
 */
object Nib {
    /** Angle of the footprint's long axis, in radians (screen y points down, so negative rises to the right). */
    const val ANGLE = -35.0 * PI / 180.0

    /** The footprint's short axis as a share of its long axis. */
    const val RATIO = 0.5

    /**
     * Where the tip lands (kihitsu) and where it presses at a stop (tome), the footprint
     * turns to lie the other way, falling to the right, which cuts the ends of a stroke
     * at the slant brush writing has.
     */
    const val TURNED = PI / 4

    /**
     * Width of the mark left by a footprint of long axis [size] at [angle] moving in
     * direction ([dx], [dy]): the footprint's extent across the direction of travel.
     */
    fun markWidth(size: Double, dx: Double, dy: Double, angle: Double = ANGLE): Double {
        val len = hypot(dx, dy)
        if (len < 1e-12) return size
        // The normal to travel, in the footprint's own axes.
        val nx = -dy / len
        val ny = dx / len
        val c = cos(angle)
        val s = sin(angle)
        val along = nx * c + ny * s
        val across = -nx * s + ny * c
        return size * sqrt(along * along + RATIO * RATIO * across * across)
    }
}

/**
 * Turns pointer input into a brush stroke. The brush's size follows speed (faster is
 * thinner) and pen pressure (fuller, but only a little: pressure can't balloon a
 * stroke); it presses in at the start and ends in a stop, a sweep or a hook. Each point
 * also records how dry the brush is running: dryness builds with speed and as the ink
 * is used up along the stroke, and peaks in a fast sweep's tail.
 */
object BrushModel {
    /** Nominal brush size (the footprint's long axis) in frame units (the character box is 109 wide). */
    const val BASE_WIDTH = 7.4

    /** Input beyond this many points is ignored (a stroke that long is not a stroke). */
    const val MAX_INPUT = 3000

    /** Hard cap on rendered samples. */
    const val MAX_SAMPLES = 12000

    /** Speed (units per ms) over which the brush thins out. */
    private const val SPEED_SCALE = 0.25

    /** Final speed above which an unrecognised stroke counts as flicked off (a sweep). */
    const val SWEEP_SPEED = 0.26

    /** Speed and pressure together keep the brush between these multiples of its nominal size. */
    const val MAX_FACTOR = 1.2
    const val MIN_FACTOR = 0.5

    /** Speeds (units per ms) between which the brush goes from wet to fully dry. */
    private const val DRY_FROM = 0.28
    private const val DRY_AT = 0.75

    /** Size factor for a given speed: slow = full and wet, fast = thinner. */
    fun speedFactor(speed: Double): Double = 0.52 + 0.6 * exp(-max(0.0, speed) / SPEED_SCALE)

    /** Pen pressure fills the brush out a little; it cannot balloon it. */
    fun pressureFactor(p: Double?): Double = if (p == null) 1.0 else 0.86 + 0.28 * p.coerceIn(0.0, 1.0)

    /**
     * The ending a well-written KanjiVG stroke type calls for: sweeps (㇒ ㇏ ㇓ ㇀ and
     * turns that finish in one) taper out, hooks flick, everything else stops.
     */
    fun endingFor(type: String): Ending = when (type.firstOrNull()) {
        '㇒', '㇏', '㇓', '㇀', '㇝', '㇇', '㇛', '㇜', '㇋', '㇌' -> Ending.SWEEP
        '㇚', '㇁', '㇂', '㇃', '㇖', '㇆', '㇈', '㇉', '㇟', '㇠', '㇡', '㇢', '㇙', '㇊' -> Ending.HOOK
        else -> Ending.STOP
    }

    /** Samples for the stroke while it is still being drawn (no ending yet). */
    fun live(input: List<InputPoint>): List<BrushSample> = shape(input, ending = null)

    /**
     * The finished stroke once the pen lifts. [ending] is the ending the stroke calls
     * for when it is known (a recognised stroke); otherwise it is read from the speed
     * at the end: a flick off the paper is a sweep, anything slower a stop.
     */
    fun finish(input: List<InputPoint>, seed: Int, ending: Ending? = null): BrushStroke {
        val e = ending ?: endingOf(input)
        return BrushStroke(shape(input, e), e, seed)
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

    private fun shape(rawInput: List<InputPoint>, ending: Ending?): List<BrushSample> {
        val input = smooth(dedupe(if (rawInput.size > MAX_INPUT) rawInput.subList(0, MAX_INPUT) else rawInput))
        if (input.isEmpty()) return emptyList()
        if (input.size == 1) {
            val p = input[0]
            return listOf(BrushSample(p.x, p.y, BASE_WIDTH * 0.9 * pressureFactor(p.pressure)))
        }
        // Size and speed per input point, smoothed so the brush breathes rather than jitters.
        val sizes = DoubleArray(input.size)
        val speeds = DoubleArray(input.size)
        var speed = 0.0
        var pressure = input[0].pressure
        var size = 0.0
        for (i in input.indices) {
            // The first point takes the first stretch's speed, so a quick stroke doesn't start with a blob.
            val a = input[max(1, i) - 1]
            val b = input[max(1, i)]
            val v = hypot(b.x - a.x, b.y - a.y) / max(1.0, b.t - a.t)
            speed = if (i <= 1) v else speed + 0.3 * (v - speed)
            if (i > 0) {
                val p = input[i].pressure
                pressure = if (p == null || pressure == null) p else pressure + 0.25 * (p - pressure)
            }
            val factor = (speedFactor(speed) * pressureFactor(pressure)).coerceIn(MIN_FACTOR, MAX_FACTOR)
            val target = BASE_WIDTH * factor
            size = if (i == 0) target else size + 0.35 * (target - size)
            sizes[i] = size
            speeds[i] = speed
        }
        val endSpeed = endSpeed(input)

        // Densify along a Catmull-Rom spline through the input points.
        val pts = ArrayList<Point>()
        val raw = ArrayList<Double>() // size before the entry and ending are shaped
        val vel = ArrayList<Double>()
        for (i in 0 until input.size - 1) {
            val p0 = input[max(0, i - 1)]; val p1 = input[i]; val p2 = input[i + 1]; val p3 = input[min(input.lastIndex, i + 2)]
            val seg = hypot(p2.x - p1.x, p2.y - p1.y)
            val step = min(0.5, max(0.12, min(sizes[i], sizes[i + 1]) * 0.22))
            val n = max(1, kotlin.math.ceil(seg / step).toInt())
            for (s in 0 until n) {
                if (pts.size >= MAX_SAMPLES) break
                val t = s.toDouble() / n
                pts.add(Point(catmull(p0.x, p1.x, p2.x, p3.x, t), catmull(p0.y, p1.y, p2.y, p3.y, t)))
                raw.add(sizes[i] + (sizes[i + 1] - sizes[i]) * t)
                vel.add(speeds[i] + (speeds[i + 1] - speeds[i]) * t)
            }
        }
        val last = input.last()
        pts.add(Point(last.x, last.y)); raw.add(sizes.last()); vel.add(speeds.last())

        // Walk the arc length to apply the entry press, the ending and the drying of the brush.
        val arc = DoubleArray(pts.size)
        for (i in 1 until pts.size) arc[i] = arc[i - 1] + pts[i].dist(pts[i - 1])
        val total = arc.last()
        val press = min(3.2, total * 0.25)
        val dot = total < 20.0 && ending == Ending.STOP
        // The footprint turns from the landing slant to the working angle, and back at a stop.
        val entryTurn = if (dot) 0.0 else min(total * 0.3, BASE_WIDTH * 1.2)
        val stopTurn = if (ending == Ending.STOP && !dot) min(total * 0.25, BASE_WIDTH * 0.9) else 0.0
        val tail = when (ending) {
            // A sweep tapers over the last third or so; a quicker flick off the paper, a little longer.
            Ending.SWEEP -> min(total * (0.3 + 0.15 * (endSpeed / 0.3).coerceIn(0.0, 1.0)), 28.0)
            Ending.HOOK -> min(total * 0.2, 7.0)
            Ending.STOP -> min(total * 0.18, 5.0)
            null -> 0.0
        }
        val out = ArrayList<BrushSample>(pts.size)
        for (i in pts.indices) {
            val a = arc[i]
            val f = if (total > 0) a / total else 0.0
            var m = 1.0
            if (!dot && press > 0 && a < press * 2) {
                // Press in: start a little narrow, swell just past the entry, settle.
                m *= if (a < press) 0.82 + 0.23 * (a / press) else 1.05 - 0.05 * ((a - press) / press)
            }
            if (ending != null && total > 0) {
                if (dot) {
                    // A dot (ten) is a teardrop: it lands light and presses down at the end.
                    m *= 0.42 + 0.55 * f
                } else if (total > 30.0) {
                    // The brush lifts a little through the middle of a long stroke.
                    m *= 1 - 0.1 * sin(f * PI)
                }
            }
            val fromEnd = total - a
            var tailDry = 0.0
            if (ending != null && tail > 0 && fromEnd < tail) {
                val u = 1 - fromEnd / tail // 0 at the tail's start … 1 at the very end
                when (ending) {
                    // Keep the body, then close to a sharp point.
                    // …and a sweep flicked off quickly runs dry in its tail.
                    Ending.SWEEP -> { m *= 1 - 0.985 * u * u * (3 - 2 * u); tailDry = 0.9 * u * ((vel[i] - 0.12) / 0.3).coerceIn(0.0, 1.0) }
                    Ending.HOOK -> m *= 1 - 0.95 * u * u
                    // The brush rests and presses a little before lifting.
                    Ending.STOP -> m *= 1 + 0.06 * sin(u * PI / 2)
                }
            }
            // Dryness: speed dries the brush, more so as the ink runs out along the stroke.
            val bySpeed = ((vel[i] - DRY_FROM) / (DRY_AT - DRY_FROM)).coerceIn(0.0, 1.0)
            // A freshly loaded brush is wet where it lands, whatever the speed.
            val dry = max(bySpeed * (0.1 + 0.9 * f), tailDry).coerceIn(0.0, 1.0)
            var angle = Nib.ANGLE
            if (a < entryTurn) angle = Nib.TURNED + (Nib.ANGLE - Nib.TURNED) * ease(a / entryTurn)
            if (fromEnd < stopTurn) angle = Nib.ANGLE + (Nib.TURNED - Nib.ANGLE) * ease(1 - fromEnd / stopTurn)
            out.add(BrushSample(pts[i].x, pts[i].y, max(0.06, raw[i] * m), dry, angle))
        }
        return out
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

    private fun ease(t: Double) = t * t * (3 - 2 * t)

    /**
     * Light smoothing of the path (a 5-point average, ends kept): the brush body has
     * some inertia, so pointer jitter shouldn't show as lumps in the ink.
     */
    private fun smooth(input: List<InputPoint>): List<InputPoint> {
        if (input.size < 5) return input
        return input.mapIndexed { i, p ->
            if (i == 0 || i == input.lastIndex) p else {
                val r = min(2, min(i, input.lastIndex - i))
                var sx = 0.0; var sy = 0.0
                for (j in i - r..i + r) { sx += input[j].x; sy += input[j].y }
                val n = 2 * r + 1
                p.copy(x = sx / n, y = sy / n)
            }
        }
    }

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t; val t3 = t2 * t
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    /**
     * Synthesises plausible brush input for a reference stroke (for the model and the
     * "show me" demo): slow at the entry, quicker through the middle, and either slowing
     * to a stop or accelerating into a sweep or a hook.
     */
    fun fromReference(points: List<Point>, type: String): List<InputPoint> {
        val ending = endingFor(type)
        val n = max(8, (Geometry.pathLength(points) / 1.5).toInt())
        val pts = Geometry.resample(points, n)
        var t = 0.0
        return pts.mapIndexed { i, p ->
            if (i > 0) {
                val u = i.toDouble() / (n - 1)
                val speed = when {
                    u < 0.12 -> 0.06 + 0.9 * u
                    ending == Ending.SWEEP -> 0.18 + 0.55 * u * u
                    ending == Ending.HOOK && u > 0.85 -> 0.2 + 1.5 * (u - 0.85)
                    u > 0.85 -> 0.17 - 0.8 * (u - 0.85)
                    else -> 0.17
                }
                t += pts[i].dist(pts[i - 1]) / max(0.04, speed)
            }
            InputPoint(p.x, p.y, t)
        }
    }

    /** The unit direction of travel at sample [i] (smoothed over a few neighbours). */
    fun direction(samples: List<BrushSample>, i: Int): Point {
        val a = samples[max(0, i - 2)]
        val b = samples[min(samples.lastIndex, i + 2)]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        return if (len < 1e-9) Point(1.0, 0.0) else Point(dx / len, dy / len)
    }
}
