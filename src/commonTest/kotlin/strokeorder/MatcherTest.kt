package strokeorder

import strokeorder.engine.Comparison
import strokeorder.engine.Compass
import strokeorder.engine.Fit
import strokeorder.engine.Geometry
import strokeorder.engine.Point
import strokeorder.engine.StrokeMatcher
import strokeorder.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    @Test
    fun aReadingThatPassesBeatsALowerScoringOneThatFails() {
        // Forward passes every measure; backward is closer overall but fails on heading.
        val forward = Fit(loc = 1.0, shape = 0.3, heading = 0.6, locTol = 10.0, shapeTol = 0.34, headingTol = 0.62)
        val backward = Fit(loc = 0.1, shape = 0.01, heading = 0.7, locTol = 10.0, shapeTol = 0.34, headingTol = 0.62)
        assertTrue(forward.passes && !backward.passes && backward.score < forward.score)
        for (directionMatters in listOf(true, false)) {
            val c = Comparison(forward, backward, directionMatters)
            assertSame(forward, c.best, "directionMatters=$directionMatters")
            assertTrue(c.passes)
            assertFalse(c.looksReversed || c.wrongDirection)
        }
        // Swapped round, the stroke is drawn backwards.
        val reversed = Comparison(backward, forward, directionMatters = true)
        assertTrue(reversed.looksReversed && reversed.wrongDirection && reversed.passes)
    }

    @Test
    fun aShortDabOnADotCountsButALineThroughItDoesNot() {
        // 火's first stroke is a dot about 20 units long; a finger often dabs far shorter.
        val dot = Fixtures.HI.strokes[0].points
        val ref = m.prepare(dot, directional = false, dot = true)
        assertTrue(ref.mark)
        val c = Geometry.centroid(Geometry.resample(dot, 32))
        val d = dot.last() - dot.first()
        val u = d * (1.0 / d.length())
        for (length in listOf(2.5, 4.0, 6.0, 10.0, 16.0, 24.0)) {
            val dab = line(c.x - u.x * length / 2, c.y - u.y * length / 2, c.x + u.x * length / 2, c.y + u.y * length / 2)
            assertTrue(m.compare(m.resample(dab), ref).passes, "a $length-unit dab")
            // …drawn either way round, since a dot's direction isn't judged
            assertTrue(m.compare(m.resample(dab.reversed()), ref).passes, "a $length-unit dab, reversed")
        }
        // A long line through the same spot is a line, not a dot.
        val long = line(c.x - u.x * 30, c.y - u.y * 30, c.x + u.x * 30, c.y + u.y * 30)
        assertFalse(m.compare(m.resample(long), ref).passes, "a 60-unit line")
        // A dab well away from the dot is somewhere else.
        assertFalse(m.compare(m.resample(line(c.x + 20, c.y + 20, c.x + 24, c.y + 24)), ref).passes, "a dab 28 units away")
    }

    @Test
    fun aShortMarkWhoseDirectionCountsIsStillCaughtBackwards() {
        // A short tick (13–18 units, not a dot) is a mark but keeps its direction.
        val tick = line(54.0, 10.0, 54.0, 24.0)
        val ref = m.prepare(tick)
        assertTrue(ref.mark && ref.directionMatters)
        assertTrue(m.compare(m.resample(line(54.0, 13.0, 54.0, 17.0)), ref).let { it.passes && !it.wrongDirection })
        assertTrue(m.compare(m.resample(line(54.0, 17.0, 54.0, 13.0)), ref).wrongDirection)
    }
}
