package com.orange.apple

/**
 * Minimal JSON parser / serializer with no external dependencies.
 *
 * Ported verbatim (package aside) from Ring's spam blocker
 * (app.ring.spamblocker.core.MiniJson) in the 2026-11 consolidation.
 * Orange has never taken a JSON library dependency, and importing one
 * for a single settings screen would break that stance; Ring's parser
 * existed precisely to avoid the same dependency there.
 *
 * Only what block-list schema v1 needs: objects, arrays, strings, numbers,
 * booleans and null. Parsed values are represented as
 * Map<String, Any?> / List<Any?> / String / Double / Boolean / null.
 * (Numbers parse as Double; epoch-millis timestamps up to 2^53 stay exact.)
 */
object MiniJson {

    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("invalid JSON: trailing characters")
        return value
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(formatNumber(value))
            is String -> writeString(sb, value)
            is Map<*, *> -> writeObject(sb, value, indent)
            is List<*> -> writeArray(sb, value, indent)
            else -> throw JsonException("unsupported type: ${value.javaClass}")
        }
    }

    private fun formatNumber(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            d.toLong().toString()
        } else {
            d.toString()
        }
    }

    private fun writeIndent(sb: StringBuilder, level: Int) {
        repeat(level * 2) { sb.append(' ') }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>, indent: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            if (indent >= 0) {
                sb.append('\n')
                writeIndent(sb, indent + 1)
            }
            writeString(sb, k.toString())
            sb.append(':')
            if (indent >= 0) sb.append(' ')
            writeValue(sb, v, if (indent >= 0) indent + 1 else -1)
        }
        if (indent >= 0) {
            sb.append('\n')
            writeIndent(sb, indent)
        }
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, list: List<*>, indent: Int) {
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        var first = true
        for (item in list) {
            if (!first) sb.append(',')
            first = false
            if (indent >= 0) {
                sb.append('\n')
                writeIndent(sb, indent + 1)
            }
            writeValue(sb, item, if (indent >= 0) indent + 1 else -1)
        }
        if (indent >= 0) {
            sb.append('\n')
            writeIndent(sb, indent)
        }
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                    text[pos] == '\n' || text[pos] == '\r')
            ) {
                pos++
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonException("invalid JSON: unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw JsonException("invalid JSON: expected object key")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw JsonException("invalid JSON: expected ':'")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonException("invalid JSON: unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw JsonException("invalid JSON: expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw JsonException("invalid JSON: unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw JsonException("invalid JSON: expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("invalid JSON: unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("invalid JSON: unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonException("invalid JSON: bad \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("invalid JSON: bad \\u escape")
                                sb.append(code.toChar())
                            }
                            else -> throw JsonException("invalid JSON: bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (text.regionMatches(pos, word, 0, word.length)) {
                pos += word.length
                return value
            }
            throw JsonException("invalid JSON: expected '$word'")
        }

        private fun parseNumber(): Double {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            while (!atEnd() && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' ||
                    text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')
            ) {
                pos++
            }
            if (start == pos) throw JsonException("invalid JSON: expected a value")
            return text.substring(start, pos).toDoubleOrNull()
                ?: throw JsonException("invalid JSON: bad number")
        }
    }
}
