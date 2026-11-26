package com.abodenestly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Quantity text <-> number, shared by the stepper and the item sheets. Locale-independent on purpose: "0,5" and "0.5"
 * both mean a half. Twins: web `lib/quantity.ts`, iOS `QuantityText`.
 */
object QuantityText {
    /** [Blank] is "no quantity". */
    sealed interface Parse {
        data object Blank : Parse
        data object Invalid : Parse
        data class Valid(val value: Double) : Parse
    }

    private val NUMBER = Regex("[0-9]+([.,][0-9]+)?")

    /** Blank, or a number above 0 with at most 3 decimals; a comma works as the decimal mark. */
    fun parse(text: String): Parse {
        val t = text.trim()
        if (t.isEmpty()) return Parse.Blank
        if (!NUMBER.matches(t)) return Parse.Invalid
        val number = BigDecimal(t.replace(',', '.'))
        if (number.signum() <= 0 || number.stripTrailingZeros().scale() > 3) return Parse.Invalid
        val value = number.toDouble()
        return if (value.isFinite()) Parse.Valid(value) else Parse.Invalid
    }

    /** The number as text: up to 3 decimals, no trailing zeros, always a dot. "" for null. */
    fun format(quantity: Double?): String {
        if (quantity == null || !quantity.isFinite()) return ""
        return BigDecimal.valueOf(quantity).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /** Stepper arithmetic on the field text: + on empty gives 1, - from 1 or below clears. Unparseable text stays as it is. */
    fun step(text: String, direction: Int): String {
        val current = when (val p = parse(text)) {
            Parse.Invalid -> return text
            Parse.Blank -> null
            is Parse.Valid -> p.value
        }
        if (direction >= 0) return format((current ?: 0.0) + 1)
        if (current == null || current - 1 <= 0) return ""
        return format(current - 1)
    }
}

/**
 * DESIGN A6 Stepper: - value + . The value is an editable decimal field, so 0.5 kg works; the caller owns the text.
 * Tapping steps once (no long-press repeat on Android). Pantry reuses it.
 */
@Composable
fun QuantityStepper(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    val tokens = LocalTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton("-", "Decrease quantity", "stepper.minus") { onValueChange(QuantityText.step(value, -1)) }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(min = 96.dp).heightIn(min = Tokens.MinTouchTarget).testTag("stepper.field"),
            label = { Text("Quantity") },
            singleLine = true,
            isError = isError,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center, color = tokens.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        StepButton("+", "Increase quantity", "stepper.plus") { onValueChange(QuantityText.step(value, 1)) }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, tag: String, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(Tokens.MinTouchTarget, Tokens.MinTouchTarget).testTag(tag).semantics { contentDescription = description },
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = tokens.accentBg, contentColor = tokens.accent),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}
