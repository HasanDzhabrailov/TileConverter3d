package com.terrainconverter.core

fun jsonObject(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = linkedMapOf(*pairs)

fun renderPrettyJson(value: Any?): String = buildString {
    appendJson(value, 0)
    append('\n')
}

private fun StringBuilder.appendJson(value: Any?, indent: Int) {
    when (value) {
        null -> append("null")
        is String -> appendQuoted(value)
        is Boolean, is Int, is Long -> append(value.toString())
        is Double -> append(value.toString())
        is Float -> append(value.toString())
        is Map<*, *> -> {
            append("{")
            if (value.isNotEmpty()) {
                append('\n')
                val entries = value.entries.toList()
                entries.forEachIndexed { index, entry ->
                    append("  ".repeat(indent + 1))
                    appendQuoted(entry.key.toString())
                    append(": ")
                    appendJson(entry.value, indent + 1)
                    if (index != entries.lastIndex) {
                        append(",")
                    }
                    append('\n')
                }
                append("  ".repeat(indent))
            }
            append("}")
        }
        is Iterable<*> -> {
            append("[")
            val items = value.toList()
            if (items.isNotEmpty()) {
                append('\n')
                items.forEachIndexed { index, item ->
                    append("  ".repeat(indent + 1))
                    appendJson(item, indent + 1)
                    if (index != items.lastIndex) {
                        append(",")
                    }
                    append('\n')
                }
                append("  ".repeat(indent))
            }
            append("]")
        }
        is Array<*> -> appendJson(value.asList(), indent)
        else -> appendQuoted(value.toString())
    }
}

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }
    append('"')
}
