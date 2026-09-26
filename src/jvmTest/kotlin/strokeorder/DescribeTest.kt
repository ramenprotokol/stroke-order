package strokeorder

import strokeorder.engine.Describe
import strokeorder.engine.Feedback
import strokeorder.engine.Practice
import strokeorder.engine.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stroke descriptions over the real KanjiVG data. They must say where a stroke really
 * is, counting the horizontal and vertical parts of turns (日's middle line is not "the
 * upper horizontal"), and every stroke of a character must read differently.
 */
class DescribeTest {
    private fun described(c: String) = KanjiSet[c].let { k -> k.strokes.indices.map { Describe.stroke(k, it) } }

    @Test
    fun ta() = assertEquals(
        listOf("the left vertical", "the across-and-down turn", "the vertical down the middle", "the horizontal across the middle", "the bottom horizontal"),
        described("田"),
    )

    @Test
    fun hi() = assertEquals(
        listOf("the left vertical", "the across-and-down turn", "the horizontal across the middle", "the bottom horizontal"),
        described("日"),
    )

    @Test
    fun me() = assertEquals(
        listOf(
            "the left vertical", "the across-and-down turn", "the second horizontal from the top",
            "the second horizontal from the bottom", "the bottom horizontal",
        ),
        described("目"),
    )

    @Test
    fun hana() = assertEquals(
        listOf(
            "the horizontal", "the upper-left vertical", "the right vertical", "the first left sweep",
            "the lower-left vertical", "the second left sweep", "the bent hook",
        ),
        described("花"),
    )

    @Test
    fun ame() = assertEquals(
        listOf(
            "the top horizontal", "the left vertical", "the hooked turn", "the vertical down the middle",
            "the upper-left dot", "the lower-left dot", "the upper-right dot", "the lower-right dot",
        ),
        described("雨"),
    )

    @Test
    fun yasumu() = assertEquals(
        listOf("the first left sweep", "the left vertical", "the horizontal", "the right vertical", "the second left sweep", "the right sweep"),
        described("休"),
    )

    @Test
    fun ji() = assertEquals(
        listOf("the vertical", "the dot", "the roof", "the horizontal hook", "the curved hook", "the lower horizontal"),
        described("字"),
    )

    @Test
    fun feedbackNamesTheStrokeByWhereItIs() {
        val ta = KanjiSet["田"]
        val p = Practice(ta)
        p.submit(ta.strokes[0].points)
        p.submit(ta.strokes[1].points)
        val v = p.submit(ta.strokes[4].points)
        assertEquals(Verdict.OutOfOrder(drawn = 4, expected = 2), v)
        assertEquals("That's stroke 5 — stroke 3 comes first (the vertical down the middle).", Feedback.forVerdict(ta, v))
    }

    @Test
    fun everyDescriptionIsUniqueWithinItsCharacter() {
        val clashes = KanjiSet.all.mapNotNull { k ->
            val d = described(k.char)
            val dupes = d.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dupes.isEmpty()) null else "${k.char}: $dupes"
        }
        assertEquals(emptyList(), clashes)
    }

    @Test
    fun noDescriptionRepeatsAWord() {
        val stop = setOf("the", "a", "and", "of")
        val repeats = KanjiSet.all.flatMap { k ->
            described(k.char).filter { d ->
                val words = d.split(Regex("[^a-z]+")).filter { it.isNotEmpty() && it !in stop }
                words.size != words.distinct().size
            }.map { "${k.char}: $it" }
        }
        assertEquals(emptyList(), repeats)
        // …and so no sentence built from them stutters either ("the left left sweep").
        val stutters = KanjiSet.all.flatMap { k ->
            k.strokes.indices.map { Feedback.forVerdict(k, Verdict.Unrecognized(it)) }
                .filter { Regex("""\b(\w+) \1\b""").containsMatchIn(it) }
        }
        assertEquals(emptyList(), stutters)
    }

    @Test
    fun everyStrokeGetsADescription() {
        for (k in KanjiSet.all) for (d in described(k.char)) assertTrue(d.startsWith("the ") && d.length > 6, "${k.char}: $d")
    }
}
