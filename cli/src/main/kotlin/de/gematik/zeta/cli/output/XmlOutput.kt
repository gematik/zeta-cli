package de.gematik.zeta.cli.output

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Node
import org.xml.sax.InputSource

private val tagStyle: TextStyle = TextColors.blue           // base0D
private val attrNameStyle: TextStyle = TextColors.yellow    // base09
private val attrValueStyle: TextStyle = TextColors.green    // base0B
private val commentStyle: TextStyle = TextColors.gray       // base03
private val declStyle: TextStyle = TextColors.magenta       // base0E

/**
 * Pretty-prints XML and applies bat/Helix-style syntax highlighting.
 *
 * Falls through gracefully:
 * - malformed XML → returned as-is (no exception),
 * - [colorize] = false → just the pretty-printed text, no ANSI.
 */
fun renderXml(xml: String, colorize: Boolean = true): String {
    val pretty = runCatching { prettyPrintXml(xml) }.getOrDefault(xml)
    return if (colorize) highlightXml(pretty) else pretty
}

private fun prettyPrintXml(xml: String): String {
    // Parse to DOM and strip whitespace-only text nodes first — otherwise the JDK's
    // INDENT=yes transformer keeps the original inter-element whitespace AND inserts its
    // own indentation, producing doubled blank lines.
    val docBuilder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
    }.newDocumentBuilder()
    val doc = docBuilder.parse(InputSource(StringReader(xml)))
    stripWhitespaceTextNodes(doc.documentElement)

    val factory = TransformerFactory.newInstance()
    runCatching { factory.setAttribute("indent-number", 2) }
    val transformer = factory.newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }
    val out = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(out))
    return out.toString()
}

private fun stripWhitespaceTextNodes(node: Node) {
    val toRemove = mutableListOf<Node>()
    val children = node.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        when (child.nodeType) {
            Node.TEXT_NODE -> if (child.textContent.isNullOrBlank()) toRemove += child
            Node.ELEMENT_NODE -> stripWhitespaceTextNodes(child)
        }
    }
    toRemove.forEach { node.removeChild(it) }
}

/**
 * Single-pass tokenizer-style highlighter. Walking once avoids the trap of running multiple
 * regex passes on the same string — once ANSI codes are embedded, later passes can match
 * across coloured spans and corrupt output.
 */
private fun highlightXml(xml: String): String = buildString {
    var i = 0
    while (i < xml.length) {
        when {
            xml.startsWith("<!--", i) -> {
                val end = xml.indexOf("-->", i + 4).let { if (it < 0) xml.length else it + 3 }
                append(commentStyle(xml.substring(i, end)))
                i = end
            }

            xml.startsWith("<?", i) -> {
                val end = xml.indexOf("?>", i + 2).let { if (it < 0) xml.length else it + 2 }
                append(declStyle(xml.substring(i, end)))
                i = end
            }

            xml.startsWith("<", i) -> {
                val end = xml.indexOf(">", i).let { if (it < 0) xml.length else it + 1 }
                append(highlightTag(xml.substring(i, end)))
                i = end
            }

            else -> {
                val nextLt = xml.indexOf("<", i).let { if (it < 0) xml.length else it }
                append(xml.substring(i, nextLt))
                i = nextLt
            }
        }
    }
}

private val tagRegex = Regex("(</?)([\\w:.-]+)([^>]*?)(/?>)")
private val attrRegex = Regex("([\\w:.-]+)\\s*=\\s*\"([^\"]*)\"")

private fun highlightTag(tag: String): String {
    val match = tagRegex.matchEntire(tag) ?: return tag
    val open = match.groupValues[1]
    val name = match.groupValues[2]
    val attrs = match.groupValues[3]
    val close = match.groupValues[4]
    return buildString {
        append(tagStyle("$open$name"))
        appendAttrs(attrs)
        append(tagStyle(close))
    }
}

private fun StringBuilder.appendAttrs(attrs: String) {
    var pos = 0
    attrRegex.findAll(attrs).forEach { m ->
        // Preserve any whitespace between attributes verbatim.
        append(attrs, pos, m.range.first)
        append(attrNameStyle(m.groupValues[1]))
        append('=')
        append(attrValueStyle("\"${m.groupValues[2]}\""))
        pos = m.range.last + 1
    }
    append(attrs, pos, attrs.length)
}
