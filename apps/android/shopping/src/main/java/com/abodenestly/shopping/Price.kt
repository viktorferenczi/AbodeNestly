package com.abodenestly.shopping

import android.text.format.DateFormat
import com.abodenestly.sync.ItemPriceRecord
import com.abodenestly.sync.StoreRecord
import com.abodenestly.sync.canonicalPrice
import com.abodenestly.sync.syncStamp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/** What the price field holds: nothing, a valid amount, or text that is not a price. */
sealed interface PriceParse {
    data object Empty : PriceParse
    data class Value(val value: BigDecimal) : PriceParse
    data object Invalid : PriceParse
}

/** The newest observation that answers a lookup: what the row caption and the sheet line show. */
data class LastBought(
    val price: BigDecimal,
    /** Millisecond UTC text. */
    val seenAt: String,
    /** The store's name while that store is live; null once it is deleted. */
    val storeName: String?,
)

/** SHOP-12 logic, kept apart from the views so it is table-testable. Twins: web `Price.ts`, iOS `Price`. */
object Price {
    // ASCII digits only (Java's \d is ASCII unless asked otherwise); `\z`, not `$`, so a trailing newline is not a price.
    private val SHAPE = Regex("^\\d+([.,]\\d{1,2})?\\z")

    /** Blank is "no price". Otherwise 0 or more with at most 2 decimals; a comma works as the decimal mark. */
    fun parse(text: String): PriceParse {
        val t = text.trim()
        if (t.isEmpty()) return PriceParse.Empty
        if (!SHAPE.matches(t)) return PriceParse.Invalid
        // From the digit string, not a Double, so 1.10 and 0.3 stay exact.
        return PriceParse.Value(canonicalPrice(BigDecimal(t.replace(',', '.'))))
    }

    /** The newest live observation of [key] at [storeId]: newest `seenAt`, ties to the newest `createdAt`, then id. */
    fun lastPrice(prices: List<ItemPriceRecord>, storeId: String, key: String): ItemPriceRecord? =
        newest(prices.filter { it.storeId == storeId && it.nameKey == key })

    /** The newest live observation of [key] at any store. */
    fun lastPriceAnywhere(prices: List<ItemPriceRecord>, key: String): ItemPriceRecord? =
        newest(prices.filter { it.nameKey == key })

    private val NEWEST = compareBy<ItemPriceRecord>({ it.seenAt }, { it.createdAt ?: "" }, { it.id })

    private fun newest(rows: List<ItemPriceRecord>): ItemPriceRecord? = rows.filter { it.deletedAt == null }.maxWithOrNull(NEWEST)

    /**
     * C2 rule: with a list store, the last price at that store; with none, the newest price at any store. The store's
     * name is left out when that store is not among the live [stores]. Null for a blank name or no price.
     */
    fun lastBought(name: String, prices: List<ItemPriceRecord>, stores: List<StoreRecord>, listStoreId: String?): LastBought? {
        val key = NameKey.make(name)
        if (key.isEmpty()) return null
        val found = (if (listStoreId != null) lastPrice(prices, listStoreId, key) else lastPriceAnywhere(prices, key)) ?: return null
        return LastBought(found.price, found.seenAt, stores.firstOrNull { it.id == found.storeId }?.name)
    }

    /** "€1.20" in the household currency; an unknown code falls back to showing the code itself. */
    fun formatMoney(amount: BigDecimal, currency: String, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currency) }
        return format.format(amount)
    }

    /** C2 caption: "€1.20 · Test Market"; a deleted store leaves just the price. */
    fun caption(last: LastBought, currency: String, locale: Locale = Locale.getDefault()): String {
        val money = formatMoney(last.price, currency, locale)
        return if (last.storeName != null) "$money · ${last.storeName}" else money
    }

    /** C3 line: "Last bought: 3 Nov, €1.20 at Test Market". */
    fun lastBoughtLine(last: LastBought, currency: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val money = formatMoney(last.price, currency, locale)
        val day = runCatching {
            val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, "dMMM") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "d MMM"
            DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(OffsetDateTime.parse(last.seenAt))
        }.getOrNull()
        return buildString {
            append("Last bought: ")
            if (day != null) append(day).append(", ")
            append(money)
            if (last.storeName != null) append(" at ").append(last.storeName)
        }
    }

    /**
     * One new observation (each price is kept, SPEC §8 ItemPrice): the full row, stored locally and queued. `name_key`
     * is the one server-owned-looking column the client sends for this table. The price is a JSON number built from the
     * `BigDecimal`'s digits (`1.1`), never from a `Double`.
     */
    fun row(id: String, householdId: String, storeId: String, name: String, price: BigDecimal, now: Instant): JsonObject {
        val stamp = syncStamp(now)
        return JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(id),
                "household_id" to JsonPrimitive(householdId),
                "store_id" to JsonPrimitive(storeId),
                "name_key" to JsonPrimitive(NameKey.make(name)),
                "price" to JsonPrimitive(canonicalPrice(price)),
                "seen_at" to JsonPrimitive(stamp),
                "deleted_at" to JsonNull,
                "created_at" to JsonPrimitive(stamp),
                "updated_at" to JsonPrimitive(stamp),
                "sync_seq" to JsonPrimitive(0),
            ),
        )
    }
}
