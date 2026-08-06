package com.fintech.mobile.core.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountParserTest {

    @Test
    fun `parses plain decimal with dot`() {
        assertEquals(1234.56, AmountParser.parse("1234.56"))
    }

    @Test
    fun `parses pt-BR decimal with comma`() {
        assertEquals(1234.56, AmountParser.parse("1234,56"))
    }

    @Test
    fun `parses pt-BR with thousand separator dot and decimal comma`() {
        assertEquals(1234.56, AmountParser.parse("1.234,56"))
    }

    @Test
    fun `parses US format with thousand separator comma and decimal dot`() {
        assertEquals(1234.56, AmountParser.parse("1,234.56"))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(AmountParser.parse("  "))
    }

    @Test
    fun `returns null for non numeric input`() {
        assertNull(AmountParser.parse("abc"))
    }
}
