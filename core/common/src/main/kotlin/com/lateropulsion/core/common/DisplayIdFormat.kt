package com.lateropulsion.core.common

/**
 * Human-readable patient display IDs of the form `LP-YYYY-NNNN` (REQ-PAT-002).
 * The sequence is allocated by the data layer; this object only formats and parses.
 */
public object DisplayIdFormat {
    private val regex = Regex("^LP-(\\d{4})-(\\d{4,})$")

    public fun format(year: Int, sequence: Int): String {
        require(year in MIN_YEAR..MAX_YEAR) { "year out of range: $year" }
        require(sequence >= 1) { "sequence must be positive: $sequence" }
        return "LP-%04d-%04d".format(year, sequence)
    }

    public fun parse(value: String): Pair<Int, Int>? {
        val m = regex.matchEntire(value.trim().uppercase()) ?: return null
        val year = m.groupValues[1].toInt()
        val seq = m.groupValues[2].toInt()
        return if (year in MIN_YEAR..MAX_YEAR && seq >= 1) year to seq else null
    }

    public fun isValid(value: String): Boolean = parse(value) != null

    private const val MIN_YEAR = 2020
    private const val MAX_YEAR = 2999
}
