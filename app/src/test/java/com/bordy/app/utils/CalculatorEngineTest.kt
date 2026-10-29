package com.bordy.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorEngineTest {

    @Test
    fun `respects operator precedence`() {
        assertEquals("14", CalculatorEngine.evaluate("2+3×4"))
    }

    @Test
    fun `respects parentheses`() {
        assertEquals("20", CalculatorEngine.evaluate("(2+3)×4"))
    }

    @Test
    fun `handles leading negative numbers`() {
        assertEquals("-3", CalculatorEngine.evaluate("-5+2"))
    }

    @Test
    fun `divides with decimals`() {
        assertEquals("2.5", CalculatorEngine.evaluate("10÷4"))
    }

    @Test
    fun `rounds recurring decimals to six places`() {
        assertEquals("0.333333", CalculatorEngine.evaluate("1÷3"))
    }

    @Test
    fun `percent divides by one hundred`() {
        assertEquals("0.5", CalculatorEngine.evaluate("50%"))
    }

    @Test
    fun `multiplies decimals`() {
        assertEquals("10", CalculatorEngine.evaluate("2.5×4"))
    }

    @Test
    fun `division by zero returns empty`() {
        assertEquals("", CalculatorEngine.evaluate("1÷0"))
    }

    @Test
    fun `incomplete expression returns empty`() {
        assertEquals("", CalculatorEngine.evaluate("2+"))
    }

    @Test
    fun `blank expression returns empty`() {
        assertEquals("", CalculatorEngine.evaluate("   "))
    }
}
