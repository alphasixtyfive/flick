package app.flick.json

import org.json.JSONTokener

/** Structural validation avoids the permissive extensions accepted by Android's JSONTokener. */
internal class JsonStructure(
    private val text: String,
    private val maxDepth: Int = 12,
    private val allowArrays: Boolean = true,
    private val maxArrayElements: Int = 512,
) {
    private var position = 0
    private var nodes = 0
    fun check() { whitespace(); require(peek() == '{') { "Expected a JSON object" }; value(0); whitespace(); require(position == text.length) { "Invalid JSON" } }
    private fun value(depth: Int) {
        require(depth <= maxDepth && ++nodes <= 12_000) { "JSON structure is too large" }
        whitespace()
        when (peek()) {
            '{' -> {
                position++; whitespace(); val keys = mutableSetOf<String>()
                if (peek() == '}') { position++; return }
                while (true) {
                    whitespace(); val key = quoted()
                    require(keys.add(key) && keys.size <= 64) { "Invalid JSON fields" }
                    whitespace(); expect(':'); value(depth + 1); whitespace()
                    if (peek() == '}') { position++; break }
                    expect(',')
                }
            }
            '[' -> {
                require(allowArrays) { "JSON arrays are not allowed here" }
                position++; whitespace(); var count = 0
                if (peek() == ']') { position++; return }
                while (true) {
                    require(++count <= maxArrayElements) { "JSON list is too large" }; value(depth + 1); whitespace()
                    if (peek() == ']') { position++; break }
                    expect(',')
                }
            }
            '"' -> quoted()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val number = NUMBER.find(text, position)
                require(number != null && number.range.first == position && number.value.length <= 64) { "Invalid JSON" }
                position += number.value.length
            }
        }
    }
    private fun quoted(): String {
        val start = position; expect('"')
        while (position < text.length) {
            val character = text[position++]
            require(character.code >= 32) { "Invalid JSON" }
            if (character == '"') {
                require(position - start <= 8192) { "JSON text is too large" }
                return JSONTokener(text.substring(start, position)).nextValue() as String
            }
            if (character == '\\') {
                val escaped = peek(); position++
                if (escaped == 'u') repeat(4) { require(peek() in "0123456789abcdefABCDEF") { "Invalid JSON" }; position++ }
                else require(escaped in "\"\\/bfnrt") { "Invalid JSON" }
            }
        }
        error("Invalid JSON")
    }
    private fun literal(value: String) { require(text.startsWith(value, position)) { "Invalid JSON" }; position += value.length }
    private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
    private fun peek() = text.getOrNull(position) ?: '\u0000'
    private fun expect(value: Char) { require(peek() == value) { "Invalid JSON" }; position++ }

    private companion object {
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
