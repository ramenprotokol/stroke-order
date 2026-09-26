package strokeorder.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in the KanjiVG frame: a 109 × 109 box, x to the right, y downwards. */
data class Point(val x: Double, val y: Double) {
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
    operator fun minus(o: Point) = Point(x - o.x, y - o.y)
    operator fun times(k: Double) = Point(x * k, y * k)
    fun dist(o: Point): Double = hypot(x - o.x, y - o.y)
    fun length(): Double = hypot(x, y)
}

data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val diagonal get() = hypot(width, height)
}

object Geometry {
    fun pathLength(pts: List<Point>): Double {
        var total = 0.0
        for (i in 1 until pts.size) total += pts[i].dist(pts[i - 1])
        return total
    }

    fun bounds(pts: List<Point>): Box {
        require(pts.isNotEmpty()) { "bounds of an empty polyline" }
        var minX = pts[0].x; var minY = pts[0].y; var maxX = minX; var maxY = minY
        for (p in pts) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return Box(minX, minY, maxX, maxY)
    }

    fun centroid(pts: List<Point>): Point {
        require(pts.isNotEmpty()) { "centroid of an empty polyline" }
        var sx = 0.0; var sy = 0.0
        for (p in pts) { sx += p.x; sy += p.y }
        return Point(sx / pts.size, sy / pts.size)
    }

    /**
     * Resamples a polyline to [n] points spaced evenly along its length (the classic
     * $1-recogniser step). The first and last points are kept exactly. A polyline with
     * no length (a tap) becomes [n] copies of its first point.
     */
    fun resample(pts: List<Point>, n: Int): List<Point> {
        require(n >= 2) { "resample needs at least 2 points, got $n" }
        require(pts.isNotEmpty()) { "cannot resample an empty polyline" }
        val total = pathLength(pts)
        if (pts.size == 1 || total == 0.0) return List(n) { pts[0] }
        val step = total / (n - 1)
        val out = ArrayList<Point>(n)
        out.add(pts[0])
        var carried = 0.0 // distance walked since the last emitted point
        var prev = pts[0]
        var i = 1
        while (i < pts.size && out.size < n - 1) {
            val cur = pts[i]
            val seg = prev.dist(cur)
            if (carried + seg >= step && seg > 0.0) {
                val t = (step - carried) / seg
                val q = Point(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                out.add(q)
                prev = q
                carried = 0.0
            } else {
                carried += seg
                prev = cur
                i++
            }
        }
        while (out.size < n - 1) out.add(pts.last())
        out.add(pts.last())
        return out
    }

    /**
     * Translates a polyline so its centroid sits at the origin and scales it so its
     * bounding-box diagonal is 1. [minExtent] stops a tiny stroke (a dot) from being
     * blown up into noise: nothing is scaled up as if it were smaller than that.
     */
    fun normalize(pts: List<Point>, minExtent: Double): List<Point> {
        val c = centroid(pts)
        val scale = 1.0 / max(bounds(pts).diagonal, minExtent)
        return pts.map { (it - c) * scale }
    }

    /** Mean distance between corresponding points of two equal-length polylines. */
    fun meanDistance(a: List<Point>, b: List<Point>): Double {
        require(a.size == b.size && a.isNotEmpty()) { "meanDistance needs equal, non-empty polylines" }
        var sum = 0.0
        for (i in a.indices) sum += a[i].dist(b[i])
        return sum / a.size
    }

    /**
     * Discrete Fréchet distance (Eiter & Mannila): the shortest "leash" that lets two
     * walkers traverse both polylines front to back without either stepping backwards.
     * Unlike a point-cloud distance it respects the order of points, so it notices a
     * stroke drawn backwards.
     */
    fun discreteFrechet(a: List<Point>, b: List<Point>): Double {
        require(a.isNotEmpty() && b.isNotEmpty()) { "discreteFrechet needs non-empty polylines" }
        val m = b.size
        var prevRow = DoubleArray(m)
        var row = DoubleArray(m)
        for (i in a.indices) {
            for (j in 0 until m) {
                val d = a[i].dist(b[j])
                row[j] = when {
                    i == 0 && j == 0 -> d
                    i == 0 -> max(row[j - 1], d)
                    j == 0 -> max(prevRow[0], d)
                    else -> max(min(min(prevRow[j], prevRow[j - 1]), row[j - 1]), d)
                }
            }
            val t = prevRow; prevRow = row; row = t
        }
        return prevRow[m - 1]
    }

    /**
     * The heading (radians) of each of [segments] equal stretches of a polyline, after a
     * light 5-point smoothing so that hand jitter does not swing short stretches around.
     */
    fun headings(pts: List<Point>, segments: Int): DoubleArray {
        require(segments >= 1) { "headings needs at least one segment" }
        val smooth = pts.indices.map { i ->
            val lo = maxOf(0, i - 2); val hi = minOf(pts.lastIndex, i + 2)
            // keep the true ends so the overall direction is not shortened
            if (i == 0 || i == pts.lastIndex) pts[i] else centroid(pts.subList(lo, hi + 1))
        }
        val r = resample(smooth, segments + 1)
        return DoubleArray(segments) { atan2(r[it + 1].y - r[it].y, r[it + 1].x - r[it].x) }
    }

    /** Mean absolute difference between two equal-length arrays of angles, each wrapped to [0, π]. */
    fun meanAngleDifference(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.isNotEmpty()) { "meanAngleDifference needs equal, non-empty arrays" }
        var sum = 0.0
        for (i in a.indices) {
            var d = abs(a[i] - b[i]) % (2 * PI)
            if (d > PI) d = 2 * PI - d
            sum += d
        }
        return sum / a.size
    }

    /** Signed turning (radians) summed along a polyline; used to tell curves from lines. */
    fun totalAbsTurning(pts: List<Point>): Double {
        var sum = 0.0
        for (i in 1 until pts.size - 1) {
            val a = pts[i] - pts[i - 1]
            val b = pts[i + 1] - pts[i]
            val la = a.length(); val lb = b.length()
            if (la == 0.0 || lb == 0.0) continue
            val cross = a.x * b.y - a.y * b.x
            val dot = a.x * b.x + a.y * b.y
            sum += abs(atan2(cross, dot))
        }
        return sum
    }
}
