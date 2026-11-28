package com.abodenestly.shopping

import com.abodenestly.sync.ListItemRecord
import com.abodenestly.ui.QuantityText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** What the C3 sheet edits, as the person typed it. [priceText] (SHOP-12) is always empty on open. */
data class ItemForm(val name: String = "", val quantityText: String = "", val unit: String = "", val note: String = "", val priceText: String = "") {
    companion object {
        fun of(item: ListItemRecord) = ItemForm(item.name, ItemSheetLogic.quantityText(item.quantity), item.unit.orEmpty(), item.note.orEmpty())
    }
}

/** What gets stored: trimmed, blanks as null. [price] (SHOP-12) is a new observation to record, not a `list_item` column. */
data class ItemFields(val name: String, val quantity: Double? = null, val unit: String? = null, val note: String? = null, val price: BigDecimal? = null)

/** The columns an edit changes, ready for `patch`; [nameKey] is the local-only `name_key` that goes with a rename. */
data class ItemChanges(val fields: JsonObject, val nameKey: String?) {
    val isEmpty: Boolean get() = fields.isEmpty()
}

/** What the sheet is open on. */
sealed interface SheetState {
    data class New(val prefill: String) : SheetState
    data class Edit(val itemId: String) : SheetState
}

/** SHOP-03 logic, kept apart from the view so it is table-testable. Twins: web `ItemSheet.ts`, iOS `ItemSheetLogic`. */
object ItemSheetLogic {
    val UNIT_PRESETS = listOf("pcs", "g", "kg", "ml", "l", "pack", "bottle", "can")

    /** Null when the form cannot be saved (blank name, bad quantity, bad price). */
    fun fields(form: ItemForm): ItemFields? {
        val name = form.name.trim()
        if (name.isEmpty()) return null
        val quantity = when (val q = QuantityText.parse(form.quantityText)) {
            QuantityText.Parse.Blank -> null
            QuantityText.Parse.Invalid -> return null
            is QuantityText.Parse.Valid -> q.value
        }
        val price = when (val p = Price.parse(form.priceText)) {
            PriceParse.Empty -> null
            PriceParse.Invalid -> return null
            is PriceParse.Value -> p.value
        }
        return ItemFields(name, quantity, blankToNull(form.unit), blankToNull(form.note), price)
    }

    /** True when the price text is not blank and not a price (drives the inline message). */
    fun priceError(text: String): Boolean = Price.parse(text) == PriceParse.Invalid

    /** True when the quantity text is not blank and not a valid quantity (drives the inline message). */
    fun quantityError(text: String): Boolean = QuantityText.parse(text) == QuantityText.Parse.Invalid

    /** The number as the field shows it: up to 3 decimals, no trailing zeros, always a dot. "" for null. */
    fun quantityText(quantity: Double?): String = QuantityText.format(quantity)

    /** "2 l", "0.5 kg", "3". A unit without a quantity shows nothing on the row. */
    fun formatQty(quantity: Double?, unit: String?): String {
        val number = quantityText(quantity)
        if (number.isEmpty()) return ""
        return if (unit.isNullOrBlank()) number else "$number $unit"
    }

    /**
     * Presets first, then the units this household already uses, without duplicates (case-insensitive), keeping those
     * that start with [typed] (case-insensitive). Deleted rows are ignored.
     */
    fun unitSuggestions(history: List<ListItemRecord>, typed: String = ""): List<String> {
        val prefix = typed.trim().lowercase()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (unit in UNIT_PRESETS + history.filter { it.deletedAt == null }.map { it.unit.orEmpty() }) {
            val t = unit.trim()
            if (t.isEmpty() || !seen.add(t.lowercase())) continue
            if (t.lowercase().startsWith(prefix)) out += t
        }
        return out
    }

    /**
     * Only the columns that changed, so editing a note does not undo a check-off made on another device. Cleared
     * columns are sent as null. A rename also returns the local-only `name_key`, which keeps suggestions matching until the next pull.
     */
    fun changes(before: ListItemRecord, after: ItemFields): ItemChanges {
        val fields = LinkedHashMap<String, JsonElement>()
        if (after.name != before.name) fields["name"] = JsonPrimitive(after.name)
        if (after.quantity != before.quantity) fields["quantity"] = after.quantity?.let { JsonPrimitive(it) } ?: JsonNull
        if (after.unit != before.unit) fields["unit"] = after.unit?.let { JsonPrimitive(it) } ?: JsonNull
        if (after.note != before.note) fields["note"] = after.note?.let { JsonPrimitive(it) } ?: JsonNull
        return ItemChanges(JsonObject(fields), if ("name" in fields) NameKey.make(after.name) else null)
    }

    private fun blankToNull(text: String): String? = text.trim().ifEmpty { null }
}
