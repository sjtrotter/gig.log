package cloud.trotter.feed.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedJsonTest {

    private fun check(text: String, depth: Int = FeedLimits.MAX_STRUCTURE_DEPTH) = FeedJson.check(text, depth)

    @Test
    fun `duplicate keys are refused at any level`() {
        assertEquals(FeedRefusal.DuplicateKey, check("""{"a":1,"a":2}"""))
        assertEquals(FeedRefusal.DuplicateKey, check("""{"a":{"b":1,"b":1}}"""))
        assertEquals(FeedRefusal.DuplicateKey, check("""{"files":[{"name":"x","name":"y"}]}"""))
        // Compared after unescaping, the way the tree parser would see them.
        assertEquals(FeedRefusal.DuplicateKey, check("""{"a":1,"a":2}"""))
        // The same key in sibling objects is not a duplicate.
        assertNull(check("""{"files":[{"name":"x"},{"name":"y"}]}"""))
    }

    @Test
    fun `depth above the bound is refused`() {
        assertNull(check("""{"a":{"b":{"c":[1]}}}"""))
        assertEquals(FeedRefusal.TooDeep, check("""{"a":{"b":{"c":[[1]]}}}"""))
        assertEquals(FeedRefusal.TooDeep, check("[[[[[]]]]]"))
        // A deep document is refused without recursion, whatever its depth.
        assertEquals(FeedRefusal.TooDeep, check("[".repeat(200_000)))
    }

    @Test
    fun `brackets and quotes inside strings do not count`() {
        assertNull(check("""{"a":"[[[[[{{{{{","b":"\"}]","c\"d":1}"""))
        assertNull(check("""{"a":"\\","b":"\\\""}"""))
    }

    @Test
    fun `numbers must be finite strict JSON numbers`() {
        assertEquals(FeedRefusal.NonFiniteNumber, check("""{"a":1e400}"""))
        assertEquals(FeedRefusal.NonFiniteNumber, check("""{"a":-1e999}"""))
        assertEquals(FeedRefusal.MalformedJson, check("""{"a":NaN}"""))
        assertEquals(FeedRefusal.MalformedJson, check("""{"a":Infinity}"""))
        assertEquals(FeedRefusal.MalformedJson, check("""{"a":01}"""))
        assertEquals(FeedRefusal.MalformedJson, check("""{"a":1.}"""))
        assertNull(check("""{"a":-0.5e-3,"b":0,"c":1794350700000}"""))
    }

    @Test
    fun `anything that is not strict JSON is refused`() {
        for (bad in listOf(
            """{"a":1,}""", """[1,]""", """{"a" 1}""", """{a:1}""", """{"a":1}x""", """{"a":1}{}""",
            """{"a":tru}""", """{"a":"x""", "\"a\nb\"", """{"a":"\x"}""", """{"a":"\u12"}""", "", "  ",
            "﻿{}", """{'a':1}""",
        )) {
            assertEquals("<$bad>", FeedRefusal.MalformedJson, check(bad))
        }
        assertNull(check(""" { "a" : [ true , false , null , "s" ] } """))
    }

    @Test
    fun `bytes must be strict UTF-8`() {
        val invalid = byteArrayOf('"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte())
        assertEquals(FeedResult.Refused(FeedRefusal.NotUtf8), FeedJson.parse(invalid, 4))
        assertNull("a lone surrogate has no UTF-8 form", FeedJson.encodeUtf8("\uD800"))
        assertTrue(FeedJson.parse("\"é\"".toByteArray(), 4) is FeedResult.Ok)
    }
}
