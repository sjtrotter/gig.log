package cloud.trotter.log.gig.core.pipeline.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) over a parsed [JsonElement] (#1630): object members sorted by their
 * names' UTF-16 code units, no whitespace, strings escaped as ECMAScript `JSON.stringify` does, and numbers in
 * ECMAScript `Number.prototype.toString` form of their IEEE 754 double. [CapabilityEnumerator]'s canonical form is
 * not JCS (it leaves keys unquoted), so this is the owner wherever an identifier is defined over JCS.
 *
 * Throws [IllegalArgumentException] on input JCS cannot encode: a non-finite number or a lone surrogate.
 */
internal object Jcs {

    fun encode(element: JsonElement): String = StringBuilder().also { append(it, element) }.toString()

    private fun append(out: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    appendString(out, key)
                    out.append(':')
                    append(out, element.getValue(key))
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                element.isString -> appendString(out, element.content)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(number(element.content))
            }
        }
    }

    private fun appendString(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { "lone surrogate" }
                    out.append(c).append(s[i + 1])
                    i++
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("lone surrogate")
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    /** ECMAScript `Number.prototype.toString` of the double [literal] denotes. */
    internal fun number(literal: String): String {
        val d = literal.toDouble()
        require(d.isFinite()) { "non-finite number" }
        if (d == 0.0) return "0"
        val sign = if (d < 0) "-" else ""
        val exact = BigDecimal(Math.abs(d))
        // The shortest digit string that reads back as the same double, rounded to nearest; 17 digits always do.
        val shortest = (1..17).asSequence()
            .map { exact.round(MathContext(it, RoundingMode.HALF_EVEN)) }
            .first { it.toDouble() == Math.abs(d) }
        val unscaled = shortest.unscaledValue().toString().trimEnd('0')
        val digits = unscaled.ifEmpty { "0" }
        val k = digits.length
        // The value is 0.<digits> × 10^n.
        val n = shortest.precision() - shortest.scale()
        return sign + when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exponent = n - 1
                val mantissa = if (k == 1) digits else "${digits[0]}." + digits.substring(1)
                mantissa + "e" + (if (exponent >= 0) "+" else "-") + Math.abs(exponent)
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
