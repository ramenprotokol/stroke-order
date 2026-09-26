package strokeorder

import strokeorder.engine.Compass
import strokeorder.engine.Point
import strokeorder.engine.StrokeMatcher
import strokeorder.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectionTest {
    @Test
    fun compassPointsFromStartToEnd() {
        assertEquals(Compass.EAST, Compass.of(line(0.0, 0.0, 10.0, 0.0)))
        assertEquals(Compass.WEST, Compass.of(line(10.0, 0.0, 0.0, 0.0)))
        assertEquals(Compass.SOUTH, Compass.of(line(0.0, 0.0, 0.0, 10.0)))
        assertEquals(Compass.NORTH, Compass.of(line(0.0, 10.0, 0.0, 0.0)))
        assertEquals(Compass.SOUTH_WEST, Compass.of(line(10.0, 0.0, 0.0, 10.0)))
        assertEquals(Compass.NORTH_EAST, Compass.of(line(0.0, 10.0, 10.0, 0.0)))
        assertNull(Compass.of(listOf(Point(1.0, 1.0), Point(1.0, 1.0))))
    }

    @Test
    fun kanjiVgStrokesHaveTheExpectedDirections() {
        assertEquals(Compass.EAST, Compass.of(Fixtures.ICHI.strokes[0].points))
        assertEquals(Compass.SOUTH, Compass.of(Fixtures.JUU.strokes[1].points))
        assertEquals(Compass.SOUTH_WEST, Compass.of(Fixtures.HITO.strokes[0].points))
        assertEquals(Compass.SOUTH_EAST, Compass.of(Fixtures.HITO.strokes[1].points))
        assertEquals(Compass.WEST, Compass.EAST.opposite)
        assertEquals(Compass.NORTH_EAST, Compass.SOUTH_WEST.opposite)
    }

    @Test
    fun matcherDetectsAReversedStroke() {
        val m = StrokeMatcher()
        for (k in Fixtures.ALL) for (s in k.strokes) {
            val ref = m.prepare(s.points)
            if (!ref.directionMatters) continue
            val forward = m.compare(m.resample(s.points), ref)
            val backward = m.compare(m.resample(s.points.reversed()), ref)
            assertFalse(forward.looksReversed, "${k.char} forward")
            assertTrue(backward.looksReversed && backward.wrongDirection, "${k.char} ${s.type} reversed")
        }
    }

    @Test
    fun dotsAreNotJudgedOnDirection() {
        val m = StrokeMatcher()
        val dot = Fixtures.HI.strokes[0].points
        val ref = m.prepare(dot, directional = false)
        assertFalse(ref.directionMatters)
        val c = m.compare(m.resample(dot.reversed()), ref)
        assertTrue(c.passes && !c.wrongDirection)
    }
}

class MatcherTest {
    private val m = StrokeMatcher()

    @Test
    fun everyReferenceStrokeMatchesItselfAlmostPerfectly() {
        for (k in Fixtures.ALL) for (s in k.strokes) {
            val c = m.compare(m.resample(s.points), m.prepare(s.points))
            assertTrue(c.passes, "${k.char}")
            assertTrue(c.forward.loc < 1e-6 && c.forward.shape < 1e-6, "${k.char}: ${c.forward}")
        }
    }

    @Test
    fun noisyScaledAndShiftedStrokesStillMatch() {
        val rng = Rng(7)
        for (k in Fixtures.ALL) for (s in k.strokes) {
            val ref = m.prepare(s.points)
            val variants = mapOf(
                "noisy" to Perturb.handDrawn(s.points, rng, sigma = 1.5),
                "scaled" to Perturb.scaleAboutCentre(s.points, 0.88),
                "shifted" to Perturb.shift(s.points, 6.0, -5.0),
                "all three" to Perturb.handDrawn(Perturb.shift(Perturb.scaleAboutCentre(s.points, 1.1), -4.0, 4.0), rng, 1.0),
            )
            for ((name, v) in variants) {
                val c = m.compare(m.resample(v), ref)
                assertTrue(c.passes && !c.wrongDirection, "${k.char} ${s.type} $name: ${c.best}")
            }
        }
    }

    @Test
    fun clearlyWrongShapesAreRejected() {
        val horizontal = m.prepare(Fixtures.ICHI.strokes[0].points)
        val wrong = mapOf(
            "vertical through the middle" to line(54.0, 12.0, 54.0, 96.0),
            "circle" to circle(54.0, 52.0, 25.0),
            "V" to line(12.0, 30.0, 54.0, 80.0) + line(54.0, 80.0, 96.0, 30.0),
        )
        for ((name, pts) in wrong) assertFalse(m.compare(m.resample(pts), horizontal).passes, name)

        // 口's second stroke turns a corner; a straight diagonal is not it.
        val turn = Fixtures.KUCHI.strokes[1].points
        val diagonal = line(turn.first().x, turn.first().y, turn.last().x, turn.last().y)
        assertFalse(m.compare(m.resample(diagonal), m.prepare(turn)).passes, "diagonal for a turn")
        // …and neither is only its first half (lifting the brush at the corner).
        assertFalse(m.compare(m.resample(turn.take(turn.size / 2)), m.prepare(turn)).passes, "half a turn")
    }
}
