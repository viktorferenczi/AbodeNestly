package com.abodenestly.sync

import java.math.BigDecimal

/**
 * A live `item_price` row as the last-price lookup reads it (SHOP-12): one observation of a name at a store. Local
 * projection, not a contract entity (same as [ListItemRecord]). [price] is a `BigDecimal`, never a float (AGENTS.md §4).
 * [seenAt] and [createdAt] are fixed-width UTC text, so comparing the text compares the time.
 */
data class ItemPriceRecord(
    val id: String,
    val storeId: String,
    /** The normalised item name (`NameKey`), as the server stores it. */
    val nameKey: String,
    val price: BigDecimal,
    val seenAt: String,
    val createdAt: String? = null,
    val deletedAt: String? = null,
)

/**
 * The one form of a money amount that is stored and sent: no trailing zeros and never an exponent ("1.10" is 1.1, 10 is
 * 10). `BigDecimal.toString()` of the result is the plain digit text, so a JSON number built from it keeps those digits.
 */
fun canonicalPrice(value: BigDecimal): BigDecimal {
    val stripped = value.stripTrailingZeros()
    return if (stripped.scale() < 0) stripped.setScale(0) else stripped
}

/** [canonicalPrice] as the plain text the local `price` column holds. */
fun priceText(value: BigDecimal): String = canonicalPrice(value).toPlainString()
