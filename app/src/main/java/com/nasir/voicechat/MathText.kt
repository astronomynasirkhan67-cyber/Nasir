package com.nasir.voicechat

/** Turns LaTeX-style math written by the model into readable plain text (x², (a)/(b), √(x), ω, Δ ...). */
object MathText {
    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
    )
    private val sym = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "leq" to "≤", "le" to "≤",
        "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "infty" to "∞", "to" to "→",
        "rightarrow" to "→", "Rightarrow" to "⇒", "leftarrow" to "←", "leftrightarrow" to "↔", "int" to "∫",
        "oint" to "∮", "sum" to "∑", "prod" to "∏", "partial" to "∂", "nabla" to "∇", "circ" to "°",
        "degree" to "°", "propto" to "∝", "equiv" to "≡", "cdots" to "…", "ldots" to "…", "dots" to "…",
        "hbar" to "ħ", "ell" to "ℓ", "in" to "∈", "forall" to "∀", "exists" to "∃", "sim" to "∼", "angle" to "∠",
        "perp" to "⊥", "parallel" to "∥", "ll" to "≪", "gg" to "≫"
    )
    private val sup = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ'
    )
    private val sub = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎'
    )

    private val FRAC = Regex("\\\\[dt]?frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}")
    private val SQRT = Regex("\\\\sqrt\\s*\\{([^{}]*)\\}")
    private val WRAP_CMD = Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname|boldsymbol|vec|hat|bar|overline)\\s*\\{([^{}]*)\\}")
    private val CMD = Regex("\\\\([A-Za-z]+)")
    private val SUP_BRACE = Regex("\\^\\s*\\{([^{}]*)\\}")
    private val SUP_ONE = Regex("\\^([0-9A-Za-z+\\-])")
    private val SUB_BRACE = Regex("_\\{([^{}]*)\\}")
    private val SUB_ONE = Regex("_([0-9])")
    private val SIMPLE = Regex("^[A-Za-z0-9.]+$")

    private fun wrap(a: String): String {
        val t = a.trim()
        return if (SIMPLE.matches(t)) t else "($t)"
    }

    private fun script(body: String, map: Map<Char, Char>, mark: String): String {
        val b = body.trim()
        if (b.isEmpty()) return ""
        if (b.all { map.containsKey(it) }) return b.map { map[it]!! }.joinToString("")
        return if (b.length == 1) mark + b else "$mark($b)"
    }

    fun clean(input: String): String {
        if (!input.any { it == '\\' || it == '$' || it == '^' || it == '_' || it == '*' }) return input
        var s = input
        s = s.replace("$$", "").replace("$", "")
            .replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "")
            .replace("**", "")
        s = s.replace(Regex("\\^\\s*\\{?\\\\circ\\}?"), "°")
        repeat(6) { s = FRAC.replace(s) { m -> wrap(m.groupValues[1]) + "/" + wrap(m.groupValues[2]) } }
        s = SQRT.replace(s) { "√(" + it.groupValues[1] + ")" }
        s = WRAP_CMD.replace(s) { it.groupValues[1] }
        s = s.replace("\\left", "").replace("\\right", "").replace("\\,", " ").replace("\\;", " ")
            .replace("\\!", "").replace("\\quad", " ").replace("\\\\", " ")
        s = CMD.replace(s) { m -> val n = m.groupValues[1]; greek[n] ?: sym[n] ?: n }
        s = SUP_BRACE.replace(s) { script(it.groupValues[1], sup, "^") }
        s = SUP_ONE.replace(s) { script(it.groupValues[1], sup, "^") }
        s = SUB_BRACE.replace(s) { script(it.groupValues[1], sub, "_") }
        s = SUB_ONE.replace(s) { script(it.groupValues[1], sub, "_") }
        s = s.replace("{", "").replace("}", "").replace("\\", "")
        return s
    }
}
