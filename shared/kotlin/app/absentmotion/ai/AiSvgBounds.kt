package app.absentmotion.ai

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Menghitung kotak batas elemen SVG ber-id (dalam koordinat SVG, tanpa memperhitungkan transform dan tebal garis). */
internal object AiSvgBounds {

    class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val centerX: Double get() = (minX + maxX) / 2.0
        val centerY: Double get() = (minY + maxY) / 2.0

        fun union(other: Box): Box = Box(min(minX, other.minX), min(minY, other.minY), max(maxX, other.maxX), max(maxY, other.maxY))
    }

    private val tokenPattern = Regex("[a-zA-Z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

    fun of(svg: String): Map<String, Box> {
        val root = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8))).documentElement
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, Box>()
        collect(root, out)
        return out
    }

    private fun collect(element: Element, out: MutableMap<String, Box>): Box? {
        var box = shapeBox(element)
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element && child.tagName != "defs") {
                val childBox = collect(child, out)
                if (childBox != null) box = if (box == null) childBox else box.union(childBox)
            }
            child = child.nextSibling
        }
        val id = element.getAttribute("id")
        if (id.isNotEmpty() && box != null) out[id] = box
        return box
    }

    private fun number(element: Element, name: String, default: Double = 0.0): Double =
        element.getAttribute(name).trim().removeSuffix("px").toDoubleOrNull() ?: default

    private fun shapeBox(e: Element): Box? = when (e.tagName) {
        "rect" -> {
            val x = number(e, "x")
            val y = number(e, "y")
            Box(x, y, x + number(e, "width"), y + number(e, "height"))
        }
        "circle" -> {
            val cx = number(e, "cx")
            val cy = number(e, "cy")
            val r = number(e, "r")
            Box(cx - r, cy - r, cx + r, cy + r)
        }
        "ellipse" -> {
            val cx = number(e, "cx")
            val cy = number(e, "cy")
            Box(cx - number(e, "rx"), cy - number(e, "ry"), cx + number(e, "rx"), cy + number(e, "ry"))
        }
        "line" -> {
            val x1 = number(e, "x1")
            val y1 = number(e, "y1")
            val x2 = number(e, "x2")
            val y2 = number(e, "y2")
            Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        }
        "polygon", "polyline" -> {
            val values = e.getAttribute("points").split(',', ' ', '\n', '\t').mapNotNull { it.trim().toDoubleOrNull() }
            var box: Box? = null
            var i = 0
            while (i + 1 < values.size) {
                box = extend(box, values[i], values[i + 1])
                i += 2
            }
            box
        }
        "path" -> pathBox(e.getAttribute("d"))
        else -> null
    }

    private fun extend(box: Box?, x: Double, y: Double): Box =
        if (box == null) Box(x, y, x, y) else Box(min(box.minX, x), min(box.minY, y), max(box.maxX, x), max(box.maxY, y))

    // ---------------------------------------------------------------- path

    private class Cursor {
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var ctrlX = 0.0
        var ctrlY = 0.0
        var box: Box? = null
        fun add(px: Double, py: Double) {
            box = extend(box, px, py)
        }
    }

    private fun pathBox(d: String): Box? {
        val tokens = tokenPattern.findAll(d).map { it.value }.toList()
        val c = Cursor()
        var i = 0
        var command = ' '
        fun num(): Double = if (i < tokens.size) tokens[i++].toDoubleOrNull() ?: 0.0 else 0.0
        fun hasNumber(): Boolean = i < tokens.size && tokens[i].toDoubleOrNull() != null
        while (i < tokens.size) {
            val token = tokens[i]
            if (token[0].isLetter() && token != "e" && token != "E") {
                command = token[0]
                i++
                if (command == 'Z' || command == 'z') {
                    c.x = c.startX
                    c.y = c.startY
                    continue
                }
            } else if (command == ' ') {
                i++
                continue
            }
            val relative = command.isLowerCase()
            val ox = if (relative) c.x else 0.0
            val oy = if (relative) c.y else 0.0
            when (command.uppercaseChar()) {
                'M' -> {
                    c.x = ox + num(); c.y = oy + num(); c.startX = c.x; c.startY = c.y; c.add(c.x, c.y)
                    command = if (relative) 'l' else 'L'
                    c.ctrlX = c.x; c.ctrlY = c.y
                }
                'L' -> { c.x = ox + num(); c.y = oy + num(); c.add(c.x, c.y); c.ctrlX = c.x; c.ctrlY = c.y }
                'H' -> { c.x = ox + num(); c.add(c.x, c.y); c.ctrlX = c.x; c.ctrlY = c.y }
                'V' -> { c.y = oy + num(); c.add(c.x, c.y); c.ctrlX = c.x; c.ctrlY = c.y }
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num(); val x = ox + num(); val y = oy + num()
                    cubic(c, c.x, c.y, x1, y1, x2, y2, x, y)
                    c.ctrlX = x2; c.ctrlY = y2; c.x = x; c.y = y
                }
                'S' -> {
                    val x1 = 2 * c.x - c.ctrlX; val y1 = 2 * c.y - c.ctrlY
                    val x2 = ox + num(); val y2 = oy + num(); val x = ox + num(); val y = oy + num()
                    cubic(c, c.x, c.y, x1, y1, x2, y2, x, y)
                    c.ctrlX = x2; c.ctrlY = y2; c.x = x; c.y = y
                }
                'Q' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x = ox + num(); val y = oy + num()
                    quad(c, c.x, c.y, x1, y1, x, y)
                    c.ctrlX = x1; c.ctrlY = y1; c.x = x; c.y = y
                }
                'T' -> {
                    val x1 = 2 * c.x - c.ctrlX; val y1 = 2 * c.y - c.ctrlY
                    val x = ox + num(); val y = oy + num()
                    quad(c, c.x, c.y, x1, y1, x, y)
                    c.ctrlX = x1; c.ctrlY = y1; c.x = x; c.y = y
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num(); val sweep = num()
                    val x = ox + num(); val y = oy + num()
                    arc(c, c.x, c.y, rx, ry, rot, large != 0.0, sweep != 0.0, x, y)
                    c.x = x; c.y = y; c.ctrlX = x; c.ctrlY = y
                }
                else -> i++
            }
            if (!hasNumber() && i < tokens.size && !tokens[i][0].isLetter()) i++
        }
        return c.box
    }

    private fun quad(c: Cursor, x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double) {
        c.add(x0, y0)
        c.add(x2, y2)
        for (t in quadExtrema(x0, x1, x2)) c.add(quadAt(x0, x1, x2, t), quadAt(y0, y1, y2, t))
        for (t in quadExtrema(y0, y1, y2)) c.add(quadAt(x0, x1, x2, t), quadAt(y0, y1, y2, t))
    }

    private fun quadExtrema(p0: Double, p1: Double, p2: Double): List<Double> {
        val denominator = p0 - 2 * p1 + p2
        if (abs(denominator) < 1e-12) return emptyList()
        val t = (p0 - p1) / denominator
        return if (t > 0 && t < 1) listOf(t) else emptyList()
    }

    private fun quadAt(p0: Double, p1: Double, p2: Double, t: Double): Double {
        val u = 1 - t
        return u * u * p0 + 2 * u * t * p1 + t * t * p2
    }

    private fun cubic(c: Cursor, x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        c.add(x0, y0)
        c.add(x3, y3)
        val ts = cubicExtrema(x0, x1, x2, x3) + cubicExtrema(y0, y1, y2, y3)
        for (t in ts) c.add(cubicAt(x0, x1, x2, x3, t), cubicAt(y0, y1, y2, y3, t))
    }

    private fun cubicExtrema(p0: Double, p1: Double, p2: Double, p3: Double): List<Double> {
        val a = -p0 + 3 * p1 - 3 * p2 + p3
        val b = 2 * (p0 - 2 * p1 + p2)
        val cc = p1 - p0
        val roots = ArrayList<Double>()
        if (abs(a) < 1e-12) {
            if (abs(b) > 1e-12) roots += -cc / b
        } else {
            val discriminant = b * b - 4 * a * cc
            if (discriminant >= 0) {
                val root = sqrt(discriminant)
                roots += (-b + root) / (2 * a)
                roots += (-b - root) / (2 * a)
            }
        }
        return roots.filter { it > 0 && it < 1 }
    }

    private fun cubicAt(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    private fun arc(c: Cursor, x1: Double, y1: Double, rxIn: Double, ryIn: Double, rotation: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
        c.add(x1, y1)
        c.add(x2, y2)
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < 1e-9 || ry < 1e-9) return
        val phi = Math.toRadians(rotation)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx = (x1 - x2) / 2
        val dy = (y1 - y2) / 2
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }
        val numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val factor = (if (large == sweep) -1.0 else 1.0) * sqrt(max(0.0, numerator / denominator))
        val cxp = factor * rx * y1p / ry
        val cyp = -factor * ry * x1p / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2
        val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val length = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            val a = acos((dot / length).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI
        val steps = 24
        for (k in 1 until steps) {
            val t = theta1 + delta * k / steps
            c.add(cx + rx * cos(t) * cosPhi - ry * sin(t) * sinPhi, cy + rx * cos(t) * sinPhi + ry * sin(t) * cosPhi)
        }
    }
}
