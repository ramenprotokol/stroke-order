package strokeorder

import strokeorder.model.Catalog
import strokeorder.model.Group
import strokeorder.model.Kana
import strokeorder.model.Kanji
import strokeorder.model.Readings
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
    fun romajiInAnySpellingBecomesKana() {
        // Hepburn and Kunrei (and Nihon-shiki) spellings of the same kana.
        for ((romaji, kana) in listOf(
            "tsu" to "つ", "tu" to "つ", "chi" to "ち", "ti" to "ち", "shi" to "し", "si" to "し",
            "ji" to "じ", "zi" to "じ", "fu" to "ふ", "hu" to "ふ", "sha" to "しゃ", "sya" to "しゃ",
            "cho" to "ちょ", "tyo" to "ちょ", "ju" to "じゅ", "zyu" to "じゅ", "du" to "づ",
            "mittsu" to "みっつ", "mittu" to "みっつ", "matcha" to "まっちゃ", "kanna" to "かんな",
            "hon" to "ほん", "hon'ya" to "ほんや", "jū" to "じゅう", "shô" to "しょう",
        )) assertEquals(kana, Kana.fromRomaji(romaji), romaji)
        assertEquals(null, Kana.fromRomaji("water"))
        assertEquals(null, Kana.fromRomaji("fire"))
    }

    @Test
    fun longVowelsFold() {
        for (r in listOf("juu", "ju")) assertEquals("ju", Kana.foldLongVowels(r))
        for (r in listOf("shou", "shoo", "sho")) assertEquals("sho", Kana.foldLongVowels(r))
        assertEquals("sei", Kana.foldLongVowels("sei"))
        assertEquals("chii", Kana.foldLongVowels("chii"))
    }

    @Test
    fun okuriganaIsShownInBrackets() {
        assertEquals("おお(きい)", Readings.display("おお.きい"))
        assertEquals("ひと", Readings.display("ひと"))
        assertEquals("ダイ・タイ", Readings.list(listOf("ダイ", "タイ")))
        assertEquals("おお・おお(きい)・おお(いに)", Readings.list(listOf("おお", "おお.きい", "おお.いに")))
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
                    k("十", "ten", listOf("ジュウ", "ジッ"), listOf("とお", "と")),
                    k("小", "small", listOf("ショウ"), listOf("ちい.さい", "こ", "お")),
                    k("月", "moon; month", listOf("ゲツ", "ガツ"), listOf("つき")),
                    k("千", "thousand", listOf("セン"), listOf("ち")),
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
    fun fullWidthAndHalfWidthInputIsFolded() {
        assertEquals(listOf("水"), chars("ｍｉｚｕ")) // full-width Latin
        assertEquals(listOf("水"), chars("ＷＡＴＥＲ"))
        assertEquals(listOf("水"), chars("ｽｲ")) // half-width katakana
        assertEquals(listOf("十"), chars("ｼﾞｭｳ")) // half-width with a separate voicing mark
        assertEquals(listOf("水"), chars("　みず　")) // ideographic spaces
    }

    @Test
    fun kunreiRomajiFindsReadings() {
        for ((kunrei, hepburn) in listOf("tuki" to "tsuki", "ti" to "chi", "syou" to "shou", "zyuu" to "juu", "hu" to "fu")) {
            assertEquals(chars(hepburn), chars(kunrei), kunrei)
        }
        assertEquals(listOf("月"), chars("tuki"))
        assertEquals(listOf("千", "小"), chars("ti")) // ち exactly, then ちい(さい) by prefix
        assertEquals(listOf("小"), chars("syou"))
        assertEquals(listOf("十"), chars("zyuu"))
    }

    @Test
    fun longVowelsMayBeMarkedDoubledOrLeftOut() {
        for (q in listOf("jū", "juu", "ju", "jû", "ジュー")) assertEquals(listOf("十"), chars(q), q)
        for (q in listOf("shō", "shou", "sho", "shô")) assertEquals(listOf("小"), chars(q), q)
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
