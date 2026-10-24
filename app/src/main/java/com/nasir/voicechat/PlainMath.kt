package com.nasir.voicechat

/** Turns LaTeX-style maths from the model into readable plain text (x², d²i/dt², √(x), π ...). */
object PlainMath {
    private const val SUP_FROM = "0123456789+-=()nitxyabckmj"
    private const val SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵗˣʸᵃᵇᶜᵏᵐʲ"
    private const val SUB_FROM = "0123456789+-=()"
    private const val SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎"

    private val WORDS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "phi" to "φ", "omega" to "ω", "Omega" to "Ω", "Delta" to "Δ", "Gamma" to "Γ", "Sigma" to "Σ", "Phi" to "Φ",
        "cdot" to "·", "times" to "×", "pm" to "±", "approx" to "≈", "neq" to "≠", "leq" to "≤", "geq" to "≥",
        "le" to "≤", "ge" to "≥", "infty" to "∞", "rightarrow" to "→", "to" to "→", "Rightarrow" to "⇒",
        "int" to "∫", "sum" to "∑", "partial" to "∂", "nabla" to "∇", "left" to "", "right" to "",
        "quad" to " ", "qquad" to " ", "ldots" to "…", "dots" to "…", "circ" to "°", "degree" to "°",
        "hbar" to "ħ", "div" to "÷", "sqrt" to "√"
    )

    private fun wrap(x: String) = if (Regex("[A-Za-z0-9.]+").matches(x.trim())) x.trim() else "(" + x.trim() + ")"

    private fun mapAll(body: String, from: String, to: String): String? {
        val sb = StringBuilder()
        for (ch in body.replace(" ", "")) {
            val i = from.indexOf(ch)
            if (i < 0) return null
            sb.append(to[i])
        }
        return sb.toString()
    }

    fun clean(input: String): String {
        var s = input.replace("**", "")
        val hadLatex = s.contains('\\') || s.contains('$') || s.contains("^{") || s.contains("_{")
        s = s.replace("$$", "").replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "")
        s = Regex("""\$([^$\n]{1,200})\$""").replace(s) { it.groupValues[1].trim() }
        repeat(4) {
            s = Regex("""\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""").replace(s) {
                wrap(it.groupValues[1]) + "/" + wrap(it.groupValues[2])
            }
        }
        s = Regex("""\\sqrt\s*\{([^{}]*)\}""").replace(s) { "√(" + it.groupValues[1] + ")" }
        s = Regex("""\\(?:text|mathrm|mathbf|mathit|boxed|operatorname)\s*\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
        s = Regex("""\^\{([^{}]*)\}|\^([0-9A-Za-z+\-])""").replace(s) {
            val body = it.groupValues[1].ifEmpty { it.groupValues[2] }
            mapAll(body, SUP_FROM, SUP_TO) ?: ("^(" + body + ")")
        }
        s = Regex("""_\{([^{}]*)\}|_([0-9])""").replace(s) {
            val body = it.groupValues[1].ifEmpty { it.groupValues[2] }
            mapAll(body, SUB_FROM, SUB_TO) ?: ("_" + body)
        }
        s = Regex("""\\([A-Za-z]+)""").replace(s) { WORDS[it.groupValues[1]] ?: it.groupValues[1] }
        s = s.replace("\\,", " ").replace("\\;", " ").replace("\\!", "").replace("\\ ", " ")
        if (hadLatex) s = s.replace("{", "").replace("}", "")
        return s
    }
}
