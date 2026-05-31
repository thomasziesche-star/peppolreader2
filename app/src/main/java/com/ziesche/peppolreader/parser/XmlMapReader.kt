package com.ziesche.peppolreader.parser

import org.xmlpull.v1.XmlPullParser

/**
 * Shared XML→Map helpers used by the invoice parsers.
 *
 * [parseDocument]/[parseElement] build a nested map keyed by element name (each
 * element wrapped under its own tag) — the shape consumed by [CiiParser] and
 * [KsefFa3Parser]. The navigation helpers ([getText], [getAttribute], [getMap],
 * [getList], [listOfMaps]) are structure-agnostic and also serve [PeppolParser],
 * which keeps its own (unwrapped) [PeppolParser.parse]-time element walker.
 */
object XmlMapReader {

    /** Reads the first top-level element of the document into a single-entry map. */
    fun parseDocument(parser: XmlPullParser): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                result.putAll(parseElement(parser))
                return result
            }
            eventType = parser.next()
        }
        return result
    }

    /** Recursively reads the current element as `{ elementName -> { children…, @attributes, #text } }`. */
    @Suppress("UNCHECKED_CAST")
    fun parseElement(parser: XmlPullParser): Map<String, Any> {
        val outer = mutableMapOf<String, Any>()
        val elementName = parser.name
        val inner = mutableMapOf<String, Any>()

        val attrs = mutableMapOf<String, String>()
        for (i in 0 until parser.attributeCount) {
            attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
        }
        if (attrs.isNotEmpty()) inner["@attributes"] = attrs

        val textBuf = StringBuilder()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_TAG || parser.name != elementName) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val childName = parser.name
                    val child = parseElement(parser)[childName] as Map<String, Any>
                    when (val existing = inner[childName]) {
                        null -> inner[childName] = child
                        is MutableList<*> -> (existing as MutableList<Map<String, Any>>).add(child)
                        is Map<*, *> -> inner[childName] = mutableListOf(existing as Map<String, Any>, child)
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) textBuf.append(text)
                }
            }
            eventType = parser.next()
        }
        if (textBuf.isNotEmpty()) inner["#text"] = textBuf.toString()

        outer[elementName] = inner
        return outer
    }

    /** Follows a `a/b/c` path and returns the leaf text (or a nested element's `#text`). */
    fun getText(map: Map<String, Any>?, path: String, default: String = ""): String {
        if (map == null) return default
        var current: Any? = map
        for (part in path.split("/")) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> (current.firstOrNull() as? Map<*, *>)?.get(part)
                else -> null
            }
            if (current == null) return default
        }
        return when (current) {
            is String -> current
            is Map<*, *> -> (current["#text"] as? String) ?: default
            else -> default
        }
    }

    /** Reads an attribute value from an element's `@attributes` bag. */
    fun getAttribute(map: Map<String, Any>?, name: String): String? {
        val attrs = map?.get("@attributes") as? Map<*, *>
        return attrs?.get(name) as? String
    }

    /** Follows a `a/b/c` path and returns the leaf element as a map. */
    @Suppress("UNCHECKED_CAST")
    fun getMap(root: Map<String, Any>?, path: String): Map<String, Any>? {
        var current: Any? = root
        for (part in path.split("/")) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> current.firstOrNull()
                else -> null
            }
            if (current == null) return null
        }
        return current as? Map<String, Any>
    }

    /** Follows a `a/b/c` path and returns the leaf as a list of maps (single map → singleton list). */
    @Suppress("UNCHECKED_CAST")
    fun getList(root: Map<String, Any>?, path: String): List<Map<String, Any>> {
        val parts = path.split("/")
        var current: Any? = root
        for ((index, part) in parts.withIndex()) {
            if (index == parts.lastIndex) {
                val items = (current as? Map<*, *>)?.get(part)
                return listOfMaps(items)
            }
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> current.firstOrNull()
                else -> null
            }
            if (current == null) return emptyList()
        }
        return emptyList()
    }

    /** Normalizes a value that may be absent, a single map, or a list into a list of maps. */
    @Suppress("UNCHECKED_CAST")
    fun listOfMaps(value: Any?): List<Map<String, Any>> = when (value) {
        null -> emptyList()
        is List<*> -> value as List<Map<String, Any>>
        is Map<*, *> -> listOf(value as Map<String, Any>)
        else -> emptyList()
    }
}
