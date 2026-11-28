package io.leancast.domain.update

/**
 * Zero-dependency parser for the cross-platform `latest.json` release manifest:
 * ```json
 * {
 *   "version": "0.4.0",
 *   "changelog": "...",
 *   "assets": {
 *     "android-universal": {
 *       "file": "LeanCast-0.4.0.apk",
 *       "sha256": "<64-char-hex>"
 *     }
 *   }
 * }
 * ```
 */
object UpdateManifestParser {

    fun parse(
        json: String,
        assetKey: String = UpdateManifest.ASSET_KEY_ANDROID_UNIVERSAL,
    ): UpdateManifest? {
        val root = runCatching { MiniJsonParser(json).parseRootObject() }.getOrNull() ?: return null
        val version = root["version"] as? String ?: return null
        val changelog = (root["changelog"] as? String) ?: ""
        @Suppress("UNCHECKED_CAST")
        val assets = root["assets"] as? Map<String, Any?> ?: return null
        @Suppress("UNCHECKED_CAST")
        val target = assets[assetKey] as? Map<String, Any?> ?: return null
        val file = target["file"] as? String ?: return null
        val sha256 = target["sha256"] as? String ?: return null
        return UpdateManifest(
            version = version,
            file = file,
            sha256 = sha256,
            changelog = changelog,
        ).validated()
    }

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parseRootObject(): Map<String, Any?> {
            skipWhitespace()
            val obj = parseObject()
            skipWhitespace()
            if (pos != src.length) error("Trailing characters at $pos")
            return obj
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) error("Unexpected EOF")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= src.length) error("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("Incomplete unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("Invalid escape '\\$esc'")
                        }
                    }
                    c < ' ' -> error("Unescaped control char")
                    else -> sb.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (peek('.')) {
                isFloat = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (peek('e') || peek('E')) {
                isFloat = true
                pos++
                if (peek('+') || peek('-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) token.toDouble() else token.toLong()
        }

        private fun <T> expectLiteral(literal: String, value: T): T {
            if (!src.startsWith(literal, pos)) error("Expected $literal at $pos")
            pos += literal.length
            return value
        }

        private fun expect(ch: Char) {
            if (pos >= src.length || src[pos] != ch) error("Expected '$ch' at $pos")
            pos++
        }

        private fun peek(ch: Char): Boolean = pos < src.length && src[pos] == ch

        private fun skipWhitespace() {
            while (pos < src.length && src[pos] in " \t\r\n") pos++
        }
    }
}
