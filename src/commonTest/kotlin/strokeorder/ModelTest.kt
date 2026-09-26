package strokeorder

import strokeorder.model.Catalog
import strokeorder.model.Group
import strokeorder.model.Kana
import strokeorder.model.Kanji
import strokeorder.model.Search
import strokeorder.model.ShareLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KanaTest {
    @Test
    fun hepburnRomaji() {
        assertEquals("hitotsu", Kana.toRomaji("ひと.つ"))
        assertEquals("kyuu", Kana.toRomaji("キュウ"))
        assertEquals("shou", Kana.toRomaji("ショウ"))
        assertEquals("chuu", Kana.toRomaji("チュウ"))
        assertEquals("ja", Kana.toRomaji("じゃ"))
        assertEquals("mittsu", Kana.toRomaji("みっつ"))
        assertEquals("matcha", Kana.toRomaji("まっちゃ"))
        assertEquals("fuji", Kana.toRomaji("ふじ"))
        assertEquals("kaa", Kana.toRomaji("カー"))
    }

    @Test
    fun katakanaFoldsToHiragana() {
        assertEquals("かわ", Kana.toHiragana("カワ"))
        assertEquals("abc", Kana.toHiragana("abc"))
    }
}

class SearchTest {
    private fun k(c: String, m: String, on: List<String>, kun: List<String>) = Kanji(c, "g", m, on, kun, emptyList())
    private val catalog = Catalog(
        listOf(
            Group(
                "nature", "Nature", "自然",
                listOf(
                    k("日", "sun; day", listOf("ニチ", "ジツ"), listOf("ひ", "か")),
                    k("火", "fire", listOf("カ"), listOf("ひ", "ほ")),
                    k("水", "water", listOf("スイ"), listOf("みず")),
                    k("一", "one", listOf("イチ"), listOf("ひと", "ひと.つ")),
                    k("人", "person", listOf("ジン", "ニン"), listOf("ひと")),
                ),
            ),
        ),
    )

    private fun chars(q: String) = catalog.search(q).map { it.char }

    @Test
    fun byMeaning() {
        assertEquals(listOf("火"), chars("fire"))
        assertEquals(listOf("火"), chars("  FIRE "))
        assertEquals(listOf("日"), chars("day"))
        assertEquals(listOf("水"), chars("wat"))
    }

    @Test
    fun byReadingInKanaOrRomaji() {
        assertEquals(listOf("日", "火"), chars("hi"))
        assertEquals(listOf("日", "火"), chars("ひ"))
        assertEquals(listOf("水"), chars("mizu"))
        assertEquals(listOf("水"), chars("スイ"))
        assertEquals(listOf("一"), chars("hitotsu"))
        assertEquals(listOf("一", "人"), chars("hito"))
    }

    @Test
    fun byCharacterAndPastedText() {
        assertEquals(listOf("水"), chars("水"))
        assertEquals(listOf("火", "水"), chars("火と水"))
    }

    @Test
    fun nothingMatchesNonsenseAndLongInputIsCapped() {
        assertEquals(emptyList(), chars("zzzz"))
        assertEquals(emptyList(), chars(""))
        assertTrue(Search.normalize("x".repeat(10_000)).length <= Search.MAX_QUERY)
    }
}

class ShareLinkTest {
    private val known = setOf("火", "水")
    private fun parse(h: String) = ShareLink.parse(h) { it in known }

    @Test
    fun roundTrips() {
        assertEquals("#k=%E7%81%AB", ShareLink.fragment("火"))
        assertEquals(ShareLink.Parsed.Valid("火"), parse(ShareLink.fragment("火")))
        assertEquals(ShareLink.Parsed.Valid("水"), parse("#k=水"))
        assertEquals(ShareLink.Parsed.None, parse(""))
        assertEquals(ShareLink.Parsed.None, parse("#"))
    }

    @Test
    fun hostileOrBrokenLinksAreRejectedWithAMessage() {
        val bad = listOf(
            "#k=" + "%E7%81%AB".repeat(50), // too long: rejected before decoding
            "#k=%E7%81", // truncated UTF-8
            "#k=%ZZ", // bad escape
            "#k=%", // dangling escape
            "#k=", // empty
            "#k=%E7%81%AB%E6%B0%B4", // two characters
            "#k=%E9%BE%8D", // 龍: real, but not in the set
            "#k=%3Cscript%3E", // markup
            "#x=1", // not a character link
        )
        for (h in bad) {
            val r = parse(h)
            assertIs<ShareLink.Parsed.Invalid>(r, h)
            assertTrue(r.message.isNotBlank())
        }
    }
}
