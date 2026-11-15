package cloud.trotter.feed.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The bounded reader for feed JSON. A linear tokenizer pre-pass runs over the text before any tree is built. It knows
 * string literals and escapes, and it refuses duplicate keys at any level (kotlinx-serialization would keep the last
 * one, compared after unescaping), nesting deeper than the caller's bound, numbers that do not fit a finite double,
 * and anything that is not strict JSON. Only text that passes reaches the tree parser.
 */
object FeedJson {

    /** Strict UTF-8 decode: a malformed or unmappable sequence is refused, never replaced. */
    fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Strict UTF-8 encode: a lone surrogate is refused, so one string has exactly one byte form. */
    fun encodeUtf8(text: String): ByteArray? = try {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    /** The pre-pass alone: null when [text] is strict JSON within [maxDepth], else the first refusal. */
    fun check(text: String, maxDepth: Int): FeedRefusal? = Scanner(text, maxDepth).run()

    /** Decode, pre-pass, then build the tree. */
    fun parse(bytes: ByteArray, maxDepth: Int): FeedResult<JsonElement> {
        val text = decodeUtf8(bytes) ?: return FeedResult.Refused(FeedRefusal.NotUtf8)
        return parse(text, maxDepth)
    }

    fun parse(text: String, maxDepth: Int): FeedResult<JsonElement> {
        check(text, maxDepth)?.let { return FeedResult.Refused(it) }
        return try {
            FeedResult.Ok(Json.parseToJsonElement(text))
        } catch (_: Exception) {
            FeedResult.Refused(FeedRefusal.MalformedJson)
        }
    }

    private enum class Expect { VALUE, VALUE_OR_CLOSE, KEY, KEY_OR_CLOSE, COLON, AFTER_VALUE }

    private class Frame(val isObject: Boolean) {
        val keys: HashSet<String>? = if (isObject) HashSet() else null
    }

    private class Scanner(private val s: String, private val maxDepth: Int) {
        private var i = 0
        private val frames = ArrayList<Frame>()

        fun run(): FeedRefusal? {
            var expect = Expect.VALUE
            while (true) {
                skipWhitespace()
                if (i >= s.length) {
                    return if (expect == Expect.AFTER_VALUE && frames.isEmpty()) null else FeedRefusal.MalformedJson
                }
                val c = s[i]
                expect = when (expect) {
                    Expect.VALUE, Expect.VALUE_OR_CLOSE ->
                        if (expect == Expect.VALUE_OR_CLOSE && c == ']') close() else value(c) ?: return refusal
                    Expect.KEY, Expect.KEY_OR_CLOSE -> when {
                        expect == Expect.KEY_OR_CLOSE && c == '}' -> close()
                        c == '"' -> {
                            val key = string(decode = true) ?: return FeedRefusal.MalformedJson
                            if (!frames.last().keys!!.add(key)) return FeedRefusal.DuplicateKey
                            Expect.COLON
                        }
                        else -> return FeedRefusal.MalformedJson
                    }
                    Expect.COLON -> if (c == ':') { i++; Expect.VALUE } else return FeedRefusal.MalformedJson
                    Expect.AFTER_VALUE -> {
                        val top = frames.lastOrNull() ?: return FeedRefusal.MalformedJson
                        when {
                            c == ',' -> { i++; if (top.isObject) Expect.KEY else Expect.VALUE }
                            c == '}' && top.isObject -> close()
                            c == ']' && !top.isObject -> close()
                            else -> return FeedRefusal.MalformedJson
                        }
                    }
                }
            }
        }

        private var refusal: FeedRefusal = FeedRefusal.MalformedJson

        private fun close(): Expect {
            frames.removeAt(frames.lastIndex)
            i++
            return Expect.AFTER_VALUE
        }

        /** Consumes one value's opening token; returns what comes next, or null with [refusal] set. */
        private fun value(c: Char): Expect? {
            refusal = FeedRefusal.MalformedJson
            return when {
                c == '{' || c == '[' -> {
                    if (frames.size >= maxDepth) {
                        refusal = FeedRefusal.TooDeep
                        return null
                    }
                    frames.add(Frame(isObject = c == '{'))
                    i++
                    if (c == '{') Expect.KEY_OR_CLOSE else Expect.VALUE_OR_CLOSE
                }
                c == '"' -> string(decode = false)?.let { Expect.AFTER_VALUE }
                c == '-' || c in '0'..'9' -> number()
                else -> literal()
            }
        }

        private fun literal(): Expect? {
            for (word in LITERALS) {
                if (s.startsWith(word, i)) {
                    i += word.length
                    return Expect.AFTER_VALUE
                }
            }
            return null
        }

        /** Strict JSON number grammar; the token must also be a finite double. */
        private fun number(): Expect? {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) return null
            if (s[i] == '0') i++ else if (!digits()) return null
            if (i < s.length && s[i] == '.') {
                i++
                if (!digits()) return null
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (!digits()) return null
            }
            if (!s.substring(start, i).toDouble().isFinite()) {
                refusal = FeedRefusal.NonFiniteNumber
                return null
            }
            return Expect.AFTER_VALUE
        }

        private fun digits(): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > start
        }

        /** Consumes a string literal at [i]; returns its decoded value (or "" when not decoding), null when malformed. */
        private fun string(decode: Boolean): String? {
            i++ // opening quote
            val out = if (decode) StringBuilder() else null
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return out?.toString() ?: ""
                    }
                    c == '\\' -> {
                        if (i + 1 >= s.length) return null
                        val e = s[i + 1]
                        val decoded = when (e) {
                            '"', '\\', '/' -> e
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                if (i + 6 > s.length) return null
                                val hex = s.substring(i + 2, i + 6)
                                if (!hex.all { it in HEX }) return null
                                i += 4
                                hex.toInt(16).toChar()
                            }
                            else -> return null
                        }
                        out?.append(decoded)
                        i += 2
                    }
                    c < ' ' -> return null
                    else -> {
                        out?.append(c)
                        i++
                    }
                }
            }
            return null
        }

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }
    }

    private val LITERALS = listOf("true", "false", "null")
    private const val HEX = "0123456789abcdefABCDEF"
}
