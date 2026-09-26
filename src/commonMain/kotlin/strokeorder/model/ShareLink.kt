package strokeorder.model

/**
 * Links to a character look like `#k=%E7%81%AB` (火). Parsing is bounded: the fragment
 * is length-checked before decoding, decoding is a small hand-written UTF-8 decoder
 * that never throws, and only one code point from the curated set is accepted.
 */
object ShareLink {
    const val MAX_FRAGMENT = 64
    private const val PREFIX = "k="

    sealed interface Parsed {
        data object None : Parsed
        data class Valid(val char: String) : Parsed
        data class Invalid(val message: String) : Parsed
    }

    fun fragment(char: String): String = "#$PREFIX" + percentEncode(char)

    fun parse(hash: String, isKnown: (String) -> Boolean): Parsed {
        if (hash.isEmpty() || hash == "#") return Parsed.None
        if (hash.length > MAX_FRAGMENT) return Parsed.Invalid("That link is too long to be a character link, so it was ignored.")
        val body = hash.removePrefix("#")
        if (!body.startsWith(PREFIX)) return Parsed.Invalid("That link isn't a character link, so it was ignored.")
        val value = percentDecode(body.removePrefix(PREFIX))
            ?: return Parsed.Invalid("That character link is garbled, so it was ignored.")
        if (value.isEmpty()) return Parsed.Invalid("That character link is empty, so it was ignored.")
        val cp = codePointCount(value)
        if (cp != 1) return Parsed.Invalid("A character link holds one character; that one holds $cp.")
        if (!isKnown(value)) {
            val shown = if (value[0].code in 0x3400..0x9FFF) "「$value」" else "That character"
            return Parsed.Invalid("$shown isn't in this set of 80 practice characters yet.")
        }
        return Parsed.Valid(value)
    }

    private fun codePointCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    private fun percentEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            // An explicit ASCII allow-list: Char.isLetterOrDigit() would pull Unicode tables into the bundle.
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') append(c) else append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** Percent-decodes UTF-8; returns null for bad escapes or invalid UTF-8. */
    fun percentDecode(s: String): String? {
        val bytes = ByteArray(s.length * 3)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = HEX.indexOf(s[i + 1].uppercaseChar())
                val lo = HEX.indexOf(s[i + 2].uppercaseChar())
                if (hi < 0 || lo < 0) return null
                bytes[n++] = ((hi shl 4) or lo).toByte()
                i += 3
            } else if (c.code < 0x80) {
                bytes[n++] = c.code.toByte()
                i++
            } else {
                // A raw non-ASCII character (some browsers leave the fragment unescaped).
                val enc = s.substring(i, if (c.isHighSurrogate() && i + 1 < s.length) i + 2 else i + 1).encodeToByteArray()
                if (n + enc.size > bytes.size) return null
                enc.copyInto(bytes, n)
                n += enc.size
                i += if (c.isHighSurrogate()) 2 else 1
            }
        }
        return try {
            bytes.copyOf(n).decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            null
        }
    }
}
