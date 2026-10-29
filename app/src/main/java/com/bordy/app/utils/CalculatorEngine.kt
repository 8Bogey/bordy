package com.bordy.app.utils

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Stack

object CalculatorEngine {

    fun evaluate(expression: String): String {
        if (expression.isBlank()) return ""
        return try {
            val tokens = tokenize(expression)
            if (tokens.isEmpty()) return ""
            val postfix = infixToPostfix(tokens)
            val result = evaluatePostfix(postfix) ?: return ""
            formatResult(result)
        } catch (e: Exception) {
            ""
        }
    }

    private fun tokenize(expr: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                // Unary minus: if '-' is at start or after an operator/bracket, and followed by a digit or '.'
                c == '-' && (tokens.isEmpty() || tokens.last() in listOf("+", "-", "×", "÷", "(")) &&
                        (i + 1 < expr.length && (expr[i + 1].isDigit() || expr[i + 1] == '.')) -> {
                    val sb = StringBuilder("-")
                    i++
                    var hasDot = false
                    while (i < expr.length && (expr[i].isDigit() || (expr[i] == '.' && !hasDot))) {
                        if (expr[i] == '.') hasDot = true
                        sb.append(expr[i])
                        i++
                    }
                    tokens.add(sb.toString())
                    continue
                }
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    var hasDot = false
                    while (i < expr.length && (expr[i].isDigit() || (expr[i] == '.' && !hasDot))) {
                        if (expr[i] == '.') hasDot = true
                        sb.append(expr[i])
                        i++
                    }
                    tokens.add(sb.toString())
                    continue
                }
                c in "+-×÷%()" -> tokens.add(c.toString())
            }
            i++
        }
        return tokens
    }

    private fun infixToPostfix(tokens: List<String>): List<String> {
        val postfix = mutableListOf<String>()
        val stack = Stack<String>()
        val precedence = mapOf("+" to 1, "-" to 1, "×" to 2, "÷" to 2, "%" to 3)

        for (token in tokens) {
            when {
                token.startsWith("-") && token.length > 1 -> postfix.add(token)
                token[0].isDigit() || token[0] == '.' -> postfix.add(token)
                token == "(" -> stack.push(token)
                token == ")" -> {
                    while (stack.isNotEmpty() && stack.peek() != "(") {
                        postfix.add(stack.pop())
                    }
                    if (stack.isNotEmpty() && stack.peek() == "(") stack.pop()
                }
                else -> {
                    while (stack.isNotEmpty() && stack.peek() != "(" &&
                        precedence.getOrDefault(token, 0) <= precedence.getOrDefault(stack.peek(), 0)
                    ) {
                        postfix.add(stack.pop())
                    }
                    stack.push(token)
                }
            }
        }
        while (stack.isNotEmpty()) {
            val op = stack.pop()
            if (op != "(" && op != ")") {
                postfix.add(op)
            }
        }
        return postfix
    }

    private fun evaluatePostfix(postfix: List<String>): BigDecimal? {
        val stack = Stack<BigDecimal>()
        for (token in postfix) {
            when (token) {
                "+" -> {
                    if (stack.size >= 2) {
                        val b = stack.pop(); val a = stack.pop()
                        stack.push(a.add(b))
                    } else return null
                }
                "-" -> {
                    if (stack.size >= 2) {
                        val b = stack.pop(); val a = stack.pop()
                        stack.push(a.subtract(b))
                    } else return null
                }
                "×" -> {
                    if (stack.size >= 2) {
                        val b = stack.pop(); val a = stack.pop()
                        stack.push(a.multiply(b))
                    } else return null
                }
                "÷" -> {
                    if (stack.size >= 2) {
                        val b = stack.pop(); val a = stack.pop()
                        if (b.compareTo(BigDecimal.ZERO) == 0) return null // قسمة على صفر
                        stack.push(a.divide(b, 10, RoundingMode.HALF_UP))
                    } else return null
                }
                "%" -> {
                    if (stack.size >= 1) {
                        val a = stack.pop()
                        stack.push(a.divide(BigDecimal(100), 10, RoundingMode.HALF_UP))
                    } else return null
                }
                else -> {
                    try {
                        stack.push(BigDecimal(token))
                    } catch (e: Exception) {
                        return null
                    }
                }
            }
        }
        return if (stack.isNotEmpty()) stack.pop() else null
    }

    private fun formatResult(result: BigDecimal): String {
        val stripped = result.stripTrailingZeros()
        return if (stripped.scale() <= 0) {
            stripped.toBigInteger().toString()
        } else {
            // حد أقصى 6 خانات عشرية لتجنب تشوه الواجهة
            val scaled = if (stripped.scale() > 6) stripped.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros() else stripped
            scaled.toPlainString()
        }
    }
}