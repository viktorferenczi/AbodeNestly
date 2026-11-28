package com.abodenestly.shopping

import java.util.Locale

/** What a spoken line came to: a name, with the quantity and unit when the person said them. */
data class SpokenItem(val name: String, val quantity: Double? = null, val unit: String? = null)

/** SHOP-14 parser. Twins: web `parseSpoken` in `VoiceAdd.ts`, iOS `VoiceAdd.parse` (DESIGN C6); keep the rules in step. */
object VoiceAdd {
    /** Spoken unit forms, each mapped to a C3 preset, so a voice item lists the same as a typed one ("litres" is stored `l`). */
    private val UNIT_WORDS: Map<String, String> = mapOf(
        "litre" to "l", "litres" to "l", "liter" to "l", "liters" to "l", "l" to "l",
        "millilitre" to "ml", "millilitres" to "ml", "milliliter" to "ml", "milliliters" to "ml", "ml" to "ml",
        "kilo" to "kg", "kilos" to "kg", "kilogram" to "kg", "kilograms" to "kg", "kg" to "kg",
        "gram" to "g", "grams" to "g", "g" to "g",
        "piece" to "pcs", "pieces" to "pcs", "pcs" to "pcs",
        "pack" to "pack", "packs" to "pack", "packet" to "pack", "packets" to "pack",
        "bottle" to "bottle", "bottles" to "bottle",
        "can" to "can", "cans" to "can", "tin" to "can", "tins" to "can",
    )

    /** One to twelve; "a" and "an" are one. "half" is handled apart because "half a kilo" has a filler word. */
    private val NUMBER_WORDS: Map<String, Double> = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
    )

    /**
     * Parses what the person said ("2 litres of milk"). Pure. Null when no name is left. Forms outside the rules
     * ("one and a half") stay in the name. [locale] cases the text; null or a blank locale means the default one.
     */
    fun parse(text: String, locale: Locale? = null): SpokenItem? {
        val loc: Locale = if (locale == null || locale.toString().isBlank()) Locale.getDefault() else locale
        val spoken: String = text.trim().lowercase(loc).split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        var quantity: Double? = null
        var rest: String = spoken

        val digits: Pair<Double, String>? = leadingNumber(spoken)
        if (digits != null) {
            if (digits.first > 0) {
                quantity = digits.first
                rest = digits.second
            }
        } else {
            val words: MutableList<String> = spoken.split(' ').toMutableList()
            val first: String? = words.firstOrNull()
            val second: String? = words.getOrNull(1)
            val number: Double? = if (first == null) null else NUMBER_WORDS[first]
            if (first == "half" || (first == "a" && second == "half")) {
                quantity = 0.5
                repeat(if (first == "half") 1 else 2) { words.removeAt(0) }
                // "half a kilo", "half a lemon": the filler article goes with the quantity.
                if ((words.firstOrNull() == "a" || words.firstOrNull() == "an") && words.size > 1) words.removeAt(0)
                rest = words.joinToString(" ")
            } else if (number != null && words.size > 1) {
                quantity = number
                rest = words.drop(1).joinToString(" ")
            }
        }

        if (quantity != null) {
            val words: MutableList<String> = rest.split(' ').filter { it.isNotEmpty() }.toMutableList()
            val mapped: String? = words.firstOrNull()?.let { UNIT_WORDS[it] }
            var unit: String? = null
            if (mapped != null && mapped in ItemSheetLogic.UNIT_PRESETS) {
                unit = mapped
                words.removeAt(0)
                if (words.firstOrNull() == "of") words.removeAt(0)
            }
            rest = words.joinToString(" ")
            if (rest.isEmpty()) return null
            return SpokenItem(capitalise(rest, loc), quantity, unit)
        }

        if (rest.isEmpty()) return null
        return SpokenItem(capitalise(rest, loc))
    }

    private val WHITESPACE = Regex("\\s+")

    private fun capitalise(text: String, loc: Locale): String {
        val head: Int = text.offsetByCodePoints(0, 1)
        return text.substring(0, head).uppercase(loc) + text.substring(head)
    }

    /**
     * A leading number: ASCII digits, then `.` or `,` and more digits. The comma is swapped for a point by hand, so the
     * result never depends on the device's decimal mark. Returns the value and the text after it, without leading spaces.
     */
    private fun leadingNumber(s: String): Pair<Double, String>? {
        fun isDigit(c: Char): Boolean = c in '0'..'9'
        var i = 0
        val number = StringBuilder()
        while (i < s.length && isDigit(s[i])) number.append(s[i++])
        if (number.isEmpty()) return null
        if (i + 1 < s.length && (s[i] == '.' || s[i] == ',') && isDigit(s[i + 1])) {
            number.append('.')
            i++
            while (i < s.length && isDigit(s[i])) number.append(s[i++])
        }
        val value: Double = number.toString().toDoubleOrNull() ?: return null
        return value to s.substring(i).trimStart()
    }
}
