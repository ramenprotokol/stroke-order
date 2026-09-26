package strokeorder

import strokeorder.engine.Geometry
import strokeorder.engine.Point
import strokeorder.engine.SvgPath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResampleTest {
    @Test
    fun resampleGivesRequestedCountAndKeepsEnds() {
        val pts = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 30.0))
        val r = Geometry.resample(pts, 32)
        assertEquals(32, r.size)
        assertEquals(pts.first(), r.first())
        assertEquals(pts.last(), r.last())
    }

    @Test
    fun resampledPointsAreEvenlySpacedAlongThePath() {
        val pts = listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(50.0, 0.0), Point(50.0, 50.0))
        val r = Geometry.resample(pts, 21)
        val step = Geometry.pathLength(pts) / 20
        for (i in 1 until r.size) assertTrue(abs(r[i].dist(r[i - 1]) - step) < 1e-6, "gap $i")
    }

    @Test
    fun resampleKeepsTotalLengthOfAStraightLine() {
        val r = Geometry.resample(line(3.0, 4.0, 93.0, 4.0, n = 7), 32)
        assertTrue(abs(Geometry.pathLength(r) - 90.0) < 1e-6)
    }

    @Test
    fun resampleOfATapRepeatsThePoint() {
        val r = Geometry.resample(listOf(Point(5.0, 5.0), Point(5.0, 5.0)), 8)
        assertEquals(List(8) { Point(5.0, 5.0) }, r)
    }

    @Test
    fun resampleRejectsNonsense() {
        assertFailsWith<IllegalArgumentException> { Geometry.resample(emptyList(), 8) }
        assertFailsWith<IllegalArgumentException> { Geometry.resample(listOf(Point(0.0, 0.0)), 1) }
    }

    @Test
    fun frechetIsZeroForIdenticalAndLargeForReversed() {
        val a = Geometry.resample(line(0.0, 0.0, 100.0, 0.0), 16)
        assertEquals(0.0, Geometry.discreteFrechet(a, a))
        // Walking one line forwards and the other backwards needs a leash as long as the line.
        assertTrue(Geometry.discreteFrechet(a, a.reversed()) >= 99.9)
        // A point cloud distance would call these the same; Fréchet does not.
        assertTrue(Geometry.meanDistance(a, a.reversed()) > 40)
    }

    @Test
    fun normalizeCentresAndScales() {
        val n = Geometry.normalize(line(10.0, 10.0, 70.0, 90.0), minExtent = 1.0)
        val c = Geometry.centroid(n)
        assertTrue(abs(c.x) < 1e-9 && abs(c.y) < 1e-9)
        assertTrue(abs(Geometry.bounds(n).diagonal - 1.0) < 1e-9)
    }
}

class SvgPathTest {
    @Test
    fun parsesKanjiVgIchiEndToEnd() {
        val pts = Fixtures.ICHI.strokes[0].points
        assertEquals(Point(11.0, 54.25), pts.first())
        val end = pts.last()
        assertTrue(abs(end.x - 96.88) < 1e-9 && abs(end.y - 50.0) < 1e-9, "end was $end")
    }

    @Test
    fun handlesAbsoluteCurvesAndPackedNumbers() {
        // "C54,69,39.62,80,21,91.5" (absolute) inside 火's third stroke
        val pts = Fixtures.HI.strokes[2].points
        assertEquals(Point(52.5, 14.25), pts.first())
        assertEquals(Point(21.0, 91.5), pts.last())
        // "0.62-5.12" and ".5.5" are two numbers each
        val packed = SvgPath.parse("M0,0l.5.5l0.62-5.12")
        assertEquals(3, packed.size)
        assertTrue(abs(packed.last().x - 1.12) < 1e-9 && abs(packed.last().y + 4.62) < 1e-9, "got ${packed.last()}")
    }

    @Test
    fun smoothCurveReflectsThePreviousControlPoint() {
        val pts = SvgPath.parse("M0,0 C0,10 10,10 10,0 S20,-10 20,0")
        assertEquals(Point(20.0, 0.0), pts.last())
        // the S segment mirrors (10,10) to (10,-10), so it bulges upwards
        assertTrue(pts.any { it.x > 10 && it.y < -5 })
    }

    @Test
    fun rejectsMalformedPaths() {
        assertFailsWith<IllegalArgumentException> { SvgPath.parse("") }
        assertFailsWith<IllegalArgumentException> { SvgPath.parse("10,10") }
        assertFailsWith<IllegalArgumentException> { SvgPath.parse("M1,2 A3,3 0 0 1 4,4") }
        assertFailsWith<IllegalArgumentException> { SvgPath.parse("M1") }
        assertFailsWith<IllegalArgumentException> { SvgPath.parse("M0,0Z 5") }
        assertFailsWith<IllegalArgumentException> { SvgPath.parse("M0,0" + "l1,1".repeat(2000)) }
    }
}
