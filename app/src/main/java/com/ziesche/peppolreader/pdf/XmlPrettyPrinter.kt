package com.ziesche.peppolreader.pdf

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Renders an invoice XML document as an HTML page with:
 *  - One CSS class per syntax kind (tag, tag name, attribute, value, text)
 *  - Indentation per element depth
 *  - Per-line `<div class="line">` wrappers so JS can filter / highlight a search query
 *  - Optional line numbers
 *
 * The output assumes namespaces stay in the source (FEATURE_PROCESS_NAMESPACES = false)
 * so the user sees the raw `cbc:` / `cac:` prefixes they get from real Peppol XML.
 */
class XmlPrettyPrinter {

    /**
     * Full HTML page with embedded CSS + JS for live search.
     * The page exposes a single window function `applyFilter(query: string)` that the
     * host (Fragment) can invoke via WebView.evaluateJavascript.
     */
    fun toHtml(xml: String, isDarkMode: Boolean, noMatchesText: String): String {
        val body = renderBody(xml)
        val palette = if (isDarkMode) DARK_PALETTE else LIGHT_PALETTE
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
$palette
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: var(--bg); color: var(--fg); }
body {
  font-family: 'JetBrains Mono', 'Roboto Mono', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  padding: 8px 4px 24px 4px;
}
.line { white-space: pre-wrap; word-break: break-word; padding: 0 4px; }
.line.hidden { display: none; }
.line-num {
  display: inline-block;
  width: 3em;
  margin-right: 8px;
  text-align: right;
  color: var(--muted);
  user-select: none;
}
.tag       { color: var(--tag); }
.tagname   { color: var(--tagname); font-weight: 600; }
.attr      { color: var(--attr); }
.attr-val  { color: var(--attrval); }
.text      { color: var(--fg); }
.comment   { color: var(--muted); font-style: italic; }
mark { background: #ffd54f; color: #000; padding: 0 1px; border-radius: 2px; }
#empty-msg {
  display: none;
  padding: 24px 12px;
  color: var(--muted);
  font-style: italic;
  text-align: center;
}
#empty-msg.visible { display: block; }
</style>
</head>
<body>
<div id="content">
$body
</div>
<div id="empty-msg">${escapeHtml(noMatchesText)}</div>
<script>
(function() {
  var emptyMsg = document.getElementById('empty-msg');
  var lines = Array.prototype.slice.call(document.querySelectorAll('.line'));

  // Cache plain text + original innerHTML once
  lines.forEach(function(line) {
    line.dataset.search = (line.textContent || '').toLowerCase();
    line.dataset.original = line.innerHTML;
  });

  function escRegex(s) {
    return s.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
  }

  function highlightTextNodes(node, regex) {
    var children = Array.prototype.slice.call(node.childNodes);
    children.forEach(function(child) {
      if (child.nodeType === 3) {
        var text = child.textContent;
        if (regex.test(text)) {
          var span = document.createElement('span');
          span.innerHTML = text.replace(regex, '<mark>${'$'}1</mark>');
          child.replaceWith.apply(child, Array.prototype.slice.call(span.childNodes));
        }
      } else if (child.nodeType === 1 && child.tagName !== 'MARK') {
        highlightTextNodes(child, regex);
      }
    });
  }

  window.applyFilter = function(query) {
    var q = (query || '').toLowerCase().trim();
    var anyVisible = false;
    var regex = q ? new RegExp('(' + escRegex(query.trim()) + ')', 'gi') : null;
    lines.forEach(function(line) {
      // Restore original innerHTML before re-applying highlighting
      line.innerHTML = line.dataset.original;
      if (!q) {
        line.classList.remove('hidden');
        anyVisible = true;
        return;
      }
      if (line.dataset.search.indexOf(q) !== -1) {
        line.classList.remove('hidden');
        highlightTextNodes(line, regex);
        anyVisible = true;
      } else {
        line.classList.add('hidden');
      }
    });
    emptyMsg.classList.toggle('visible', !anyVisible);
  };
})();
</script>
</body>
</html>
""".trimIndent()
    }

    /** Builds just the `<div class="line">…</div>` rows (no surrounding HTML). */
    private fun renderBody(xml: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // Keep prefixes (cbc:, cac:, ram:) visible in the raw view.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val out = StringBuilder()
        var depth = 0
        var lineNumber = 1
        // XmlPullParser does not expose the XML prolog as an event; render it manually.
        out.append(line(lineNumber++, 0) {
            append("<span class='tag'>&lt;?</span>")
            append("<span class='tagname'>xml</span>")
            append(" <span class='attr'>version=</span><span class='attr-val'>\"1.0\"</span>")
            append(" <span class='attr'>encoding=</span><span class='attr-val'>\"UTF-8\"</span>")
            append("<span class='tag'>?&gt;</span>")
        })

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        out.append(line(lineNumber++, depth) {
                            append("<span class='tag'>&lt;</span>")
                            append("<span class='tagname'>${escapeHtml(name)}</span>")
                            for (i in 0 until parser.attributeCount) {
                                val attrName = parser.getAttributeName(i)
                                val attrValue = parser.getAttributeValue(i)
                                append(" <span class='attr'>${escapeHtml(attrName)}=</span>")
                                append("<span class='attr-val'>\"${escapeHtml(attrValue)}\"</span>")
                            }
                            append("<span class='tag'>&gt;</span>")
                        })
                        depth++
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            out.append(line(lineNumber++, depth) {
                                append("<span class='text'>${escapeHtml(text)}</span>")
                            })
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        val name = parser.name
                        out.append(line(lineNumber++, depth) {
                            append("<span class='tag'>&lt;/</span>")
                            append("<span class='tagname'>${escapeHtml(name)}</span>")
                            append("<span class='tag'>&gt;</span>")
                        })
                    }
                    XmlPullParser.COMMENT -> {
                        val c = parser.text.orEmpty()
                        out.append(line(lineNumber++, depth) {
                            append("<span class='comment'>&lt;!--${escapeHtml(c)}--&gt;</span>")
                        })
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Fall back gracefully: append the unparsed remainder as a single text line.
            out.append(line(lineNumber, depth) {
                append("<span class='comment'>")
                append(escapeHtml("(XML parse error: ${e.message ?: e.javaClass.simpleName})"))
                append("</span>")
            })
        }
        return out.toString()
    }

    private inline fun line(
        number: Int,
        depth: Int,
        content: StringBuilder.() -> Unit
    ): String {
        val indent = "  ".repeat(depth)
        val sb = StringBuilder()
        sb.append("<div class=\"line\"><span class=\"line-num\">").append(number).append("</span>")
        sb.append(indent)
        sb.content()
        sb.append("</div>\n")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    companion object {
        private val LIGHT_PALETTE = """
            :root {
              --bg: #FAF9F5;
              --fg: #1A1A1A;
              --muted: #888;
              --tag: #888;
              --tagname: #1e6fb8;
              --attr: #c45500;
              --attrval: #2a7a2a;
            }
        """.trimIndent()

        private val DARK_PALETTE = """
            :root {
              --bg: #141413;
              --fg: #E8E6DC;
              --muted: #8a8a82;
              --tag: #888;
              --tagname: #6db5ff;
              --attr: #e8a04a;
              --attrval: #8acf8a;
            }
        """.trimIndent()
    }
}
