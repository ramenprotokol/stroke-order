package strokeorder

import strokeorder.engine.Geometry
import strokeorder.engine.Point
import strokeorder.util.Rng
import kotlin.math.cos
import kotlin.math.sin

/** Ways to make a reference stroke look hand-drawn. */
object Perturb {
    private val centre = Point(54.5, 54.5)

    fun scaleAboutCentre(pts: List<Point>, s: Double) = pts.map { centre + (it - centre) * s }

    fun shift(pts: List<Point>, dx: Double, dy: Double) = pts.map { Point(it.x + dx, it.y + dy) }

    fun rotate(pts: List<Point>, degrees: Double): List<Point> {
        val a = degrees * kotlin.math.PI / 180
        val c = cos(a); val s = sin(a)
        return pts.map { val d = it - centre; Point(centre.x + d.x * c - d.y * s, centre.y + d.x * s + d.y * c) }
    }

    /** Re-samples like a pointer would (uneven spacing) and adds hand jitter of [sigma] units. */
    fun handDrawn(pts: List<Point>, rng: Rng, sigma: Double, samples: Int = 60): List<Point> {
        val even = Geometry.resample(pts, samples)
        return even.mapIndexed { i, p ->
            // keep the ends still-ish: people start and stop deliberately
            val k = if (i == 0 || i == even.lastIndex) 0.4 else 1.0
            Point(p.x + rng.gaussian() * sigma * k, p.y + rng.gaussian() * sigma * k)
        }
    }

    /** Everything at once: a smaller, shifted, slightly rotated, jittery copy of each stroke. */
    fun sloppy(strokes: List<List<Point>>, seed: Int, scale: Double = 0.84, dx: Double = 5.0, dy: Double = -4.0, deg: Double = 3.0, sigma: Double = 1.2): List<List<Point>> {
        val rng = Rng(seed)
        return strokes.map { handDrawn(shift(rotate(scaleAboutCentre(it, scale), deg), dx, dy), rng, sigma) }
    }
}

fun circle(cx: Double, cy: Double, r: Double, n: Int = 40) =
    (0..n).map { i -> val a = 2 * kotlin.math.PI * i / n; Point(cx + r * cos(a), cy + r * sin(a)) }

fun line(x0: Double, y0: Double, x1: Double, y1: Double, n: Int = 20) =
    (0..n).map { i -> val t = i.toDouble() / n; Point(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t) }
