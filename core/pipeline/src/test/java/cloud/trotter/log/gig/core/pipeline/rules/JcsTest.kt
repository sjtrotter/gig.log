package cloud.trotter.log.gig.core.pipeline.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** #1630: [Jcs] against the vectors of RFC 8785 itself, for what rule JSON can contain. */
class JcsTest {

    private fun jcs(json: String) = Jcs.encode(Json.parseToJsonElement(json))

    /** A JSON `\\uXXXX` escape, built at run time so the source holds no escape a tool could rewrite. */
    private fun u(hex: String) = "\\" + "u" + hex

    @Test
    fun `RFC 8785 section 3_2_2 sample`() {
        val string = "\"" + u("20ac") + "$" + u("000F") + u("000a") + "A'" + u("0042") + u("0022") + u("005c") +
            "\\\\" + "\\\"" + "\\/" + "\""
        val input = """{ "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001], """ +
            """"string": $string, "literals": [null, true, false] }"""
        val euro = Json.parseToJsonElement("\"" + u("20ac") + "\"").let { (it as JsonPrimitive).content }
        assertEquals(
            """{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],""" +
                "\"string\":\"" + euro + "$" + u("000f") + "\\nA'B\\\"\\\\\\\\\\\"/\"}",
            jcs(input),
        )
    }

    @Test
    fun `RFC 8785 section 3_2_3 member order is by UTF-16 code units`() {
        val input = """
            { "€": "Euro Sign", "\r": "Carriage Return", "דּ": "Hebrew Letter Dalet With Dagesh",
              "1": "One", "😀": "Emoji: Grinning Face", "\u0080": "Control",
              "ö": "Latin Small Letter O With Diaeresis" }
        """.trimIndent()
        val keys = Json.parseToJsonElement(jcs(input)).jsonObject.keys.toList()
        assertEquals(listOf("\r", "1", "\u0080", "ö", "€", "😀", "דּ"), keys)
    }

    @Test
    fun `RFC 8785 appendix B numbers`() {
        val vectors = listOf(
            "0" to "0",
            "-0" to "0",
            "1" to "1",
            "-1" to "-1",
            "100" to "100",
            "9007199254740991" to "9007199254740991",
            "295147905179352830000" to "295147905179352830000",
            "1e21" to "1e+21",
            "5e-324" to "5e-324",
            "1.7976931348623157e308" to "1.7976931348623157e+308",
            "-1.7976931348623157e308" to "-1.7976931348623157e+308",
            "9.999999999999997e22" to "9.999999999999997e+22",
            "1e23" to "1e+23",
            "0.000001" to "0.000001",
            "0.0000001" to "1e-7",
            "0.5" to "0.5",
            "1.5" to "1.5",
            "123.456" to "123.456",
        )
        for ((literal, expected) in vectors) assertEquals(literal, expected, Jcs.number(literal))
    }

    @Test
    fun `control characters escape, other text passes through`() {
        assertEquals("\"\\b\\t\\n\\f\\r\\u0001\\u001f\"", Jcs.encode(JsonPrimitive("\b\t\n\u000C\r\u0001\u001f")))
        assertEquals("\"/é😀\"", Jcs.encode(JsonPrimitive("/é😀")))
    }

    @Test
    fun `a lone surrogate or a non-finite number is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Jcs.encode(JsonPrimitive("a\ud83db")) }
        assertThrows(IllegalArgumentException::class.java) { Jcs.encode(JsonPrimitive("\ude00")) }
        assertThrows(IllegalArgumentException::class.java) { Jcs.number("1e400") }
    }
}
