package app.absentmotion.ai

import org.json.JSONArray
import org.json.JSONObject

internal object AiLoop {

    private const val MAX_COUNT = 200
    private const val MAX_ACTIONS = 600
    private const val MAX_DEPTH = 2
    private val braces = Regex("\\{([^{}]+)\\}")

    fun expandAll(items: List<JSONObject>, skipped: MutableList<Pair<String, String>>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (item in items) {
            if (item.optString("aksi", "").lowercase() == "ulangi") {
                val expanded = ArrayList<JSONObject>()
                try {
                    expandLoop(item, emptyMap(), expanded, 0)
                    out += expanded
                } catch (e: IllegalArgumentException) {
                    skipped += "ulangi" to (e.message ?: "Perulangan tidak valid")
                }
            } else {
                out += item
            }
            if (out.size > MAX_ACTIONS) {
                skipped += "ulangi" to "Terlalu banyak aksi (maksimal $MAX_ACTIONS), sisanya dilewati"
                return out.take(MAX_ACTIONS)
            }
        }
        return out
    }

    private fun expandLoop(spec: JSONObject, env: Map<String, Double>, out: MutableList<JSONObject>, depth: Int) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("ulangi: terlalu banyak tingkat bersarang (maksimal 3)")
        val count = (number(spec.opt("jumlah"), env) ?: throw IllegalArgumentException("ulangi: 'jumlah' harus angka")).toInt()
        if (count < 1 || count > MAX_COUNT) throw IllegalArgumentException("ulangi: jumlah harus 1 sampai $MAX_COUNT")
        val start = number(spec.opt("mulai"), env) ?: 0.0
        val step = number(spec.opt("langkah"), env) ?: 1.0
        val body = spec.optJSONArray("isi") ?: throw IllegalArgumentException("ulangi: field 'isi' tidak ada")
        for (k in 0 until count) {
            val vars = HashMap(env)
            vars["k"] = k.toDouble()
            vars["i"] = start + k * step
            vars["n"] = count.toDouble()
            for (j in 0 until body.length()) {
                val template = body.optJSONObject(j) ?: throw IllegalArgumentException("ulangi: elemen 'isi' bukan objek")
                if (template.optString("aksi", "").lowercase() == "ulangi") {
                    expandLoop(template, vars, out, depth + 1)
                } else {
                    out += substitute(template, vars) as JSONObject
                }
                if (out.size > MAX_ACTIONS) return
            }
        }
    }

    private fun number(raw: Any?, env: Map<String, Double>): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> if (raw.startsWith("=")) Expr(raw.substring(1), env).eval() else raw.trim().toDoubleOrNull()
        else -> null
    }

    private fun substitute(value: Any?, vars: Map<String, Double>): Any? = when (value) {
        is String -> substituteText(value, vars)
        is JSONObject -> {
            val copy = JSONObject()
            for (key in value.keys()) copy.put(key, substitute(value.opt(key), vars))
            copy
        }
        is JSONArray -> {
            val copy = JSONArray()
            for (i in 0 until value.length()) copy.put(substitute(value.opt(i), vars))
            copy
        }
        else -> value
    }

    private fun substituteText(text: String, vars: Map<String, Double>): Any {
        if (text.startsWith("=")) return clean(Expr(text.substring(1), vars).eval())
        if (!text.contains('{')) return text
        return braces.replace(text) { match ->
            try {
                clean(Expr(match.groupValues[1], vars).eval()).toString()
            } catch (_: IllegalArgumentException) {
                match.value
            }
        }
    }

    private fun clean(value: Double): Any {
        if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("Hasil rumus bukan angka yang valid")
        val rounded = Math.round(value * 10000.0) / 10000.0
        return if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e12) rounded.toLong() else rounded
    }

    private class Expr(private val src: String, private val vars: Map<String, Double>) {
        private var pos = 0

        fun eval(): Double {
            val value = sum()
            if (peek() != '\u0000') fail("karakter tidak dikenal '${src[pos]}'")
            return value
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("Rumus '$src': $message")

        private fun peek(): Char {
            while (pos < src.length && src[pos] == ' ') pos++
            return if (pos < src.length) src[pos] else '\u0000'
        }

        private fun sum(): Double {
            var value = product()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; value += product() }
                    '-' -> { pos++; value -= product() }
                    else -> return value
                }
            }
        }

        private fun product(): Double {
            var value = power()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; value *= power() }
                    '/' -> {
                        pos++
                        val divisor = power()
                        if (divisor == 0.0) fail("pembagian dengan nol")
                        value /= divisor
                    }
                    '%' -> {
                        pos++
                        val divisor = power()
                        if (divisor == 0.0) fail("pembagian dengan nol")
                        value -= divisor * Math.floor(value / divisor)
                    }
                    else -> return value
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                pos++
                return Math.pow(base, power())
            }
            return base
        }

        private fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            else -> primary()
        }

        private fun primary(): Double {
            val c = peek()
            if (c == '(') {
                pos++
                val value = sum()
                if (peek() != ')') fail("kurung tutup hilang")
                pos++
                return value
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                return src.substring(start, pos).toDoubleOrNull() ?: fail("angka tidak valid")
            }
            if (c.isLetter() || c == '_') {
                val start = pos
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
                val name = src.substring(start, pos).lowercase()
                if (peek() == '(') {
                    pos++
                    val args = ArrayList<Double>()
                    if (peek() != ')') {
                        while (true) {
                            args += sum()
                            if (peek() == ',') pos++ else break
                        }
                    }
                    if (peek() != ')') fail("kurung tutup hilang")
                    pos++
                    return call(name, args)
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> vars[name] ?: fail("variabel '$name' tidak dikenal (pakai i, k, atau n)")
                }
            }
            fail("rumus tidak lengkap")
        }

        private fun need(name: String, args: List<Double>, count: Int) {
            if (args.size != count) fail("$name butuh $count argumen")
        }

        private fun call(name: String, args: List<Double>): Double = when (name) {
            "sin" -> { need(name, args, 1); Math.sin(Math.toRadians(args[0])) }
            "cos" -> { need(name, args, 1); Math.cos(Math.toRadians(args[0])) }
            "tan" -> { need(name, args, 1); Math.tan(Math.toRadians(args[0])) }
            "abs" -> { need(name, args, 1); Math.abs(args[0]) }
            "sqrt" -> { need(name, args, 1); if (args[0] < 0) fail("sqrt dari angka negatif"); Math.sqrt(args[0]) }
            "round" -> { need(name, args, 1); Math.rint(args[0]) }
            "floor" -> { need(name, args, 1); Math.floor(args[0]) }
            "ceil" -> { need(name, args, 1); Math.ceil(args[0]) }
            "pow" -> { need(name, args, 2); Math.pow(args[0], args[1]) }
            "mod" -> {
                need(name, args, 2)
                if (args[1] == 0.0) fail("mod dengan nol")
                args[0] - args[1] * Math.floor(args[0] / args[1])
            }
            "min" -> { if (args.size < 2) fail("min butuh minimal 2 argumen"); args.min() }
            "max" -> { if (args.size < 2) fail("max butuh minimal 2 argumen"); args.max() }
            "clamp" -> { need(name, args, 3); Math.max(args[1], Math.min(args[2], args[0])) }
            "acak" -> {
                need(name, args, 1)
                val s = Math.sin(args[0] * 12.9898) * 43758.5453
                s - Math.floor(s)
            }
            else -> fail("fungsi '$name' tidak dikenal")
        }
    }
}
