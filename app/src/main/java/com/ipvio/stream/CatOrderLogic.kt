package com.ipvio.stream

/**
 * Order of provider categories (Live TV, movies, series): provider order, A–Z, or an own order the user built by
 * moving categories. Without Android, testable. Stored by [CatOrder].
 */
object CatOrderLogic {
    const val PROVIDER = 0
    const val AZ = 1
    const val OWN = 2

    private val az = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }

    /**
     * [names] in the order the provider delivers them. OWN: categories from [own] first (in that order), categories
     * the user has not placed yet (new at the provider) keep their provider order behind them.
     */
    fun apply(names: List<String>, mode: Int, own: List<String>): List<String> = when (mode) {
        AZ -> names.sortedWith(az)
        OWN -> {
            val present = names.toHashSet()
            val placed = own.filter { it in present }.distinct()
            val placedSet = placed.toHashSet()
            placed + names.filter { it !in placedSet }
        }
        else -> names
    }

    /** [shown] = order on screen. Moves [name] by [delta] places (negative = up). Unknown name: unchanged. */
    fun move(shown: List<String>, name: String, delta: Int): List<String> {
        val i = shown.indexOf(name)
        if (i < 0 || delta == 0) return shown
        val j = (i + delta).coerceIn(0, shown.lastIndex)
        if (j == i) return shown
        val out = shown.toMutableList()
        out.removeAt(i)
        out.add(j, name)
        return out
    }

    /** Moves [name] to the first place. */
    fun toTop(shown: List<String>, name: String): List<String> = move(shown, name, -shown.size)

    /** Stored form: one name per line (category names never contain a line break). */
    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(s: String?): List<String> = s.orEmpty().split('\n').filter { it.isNotBlank() }
}
