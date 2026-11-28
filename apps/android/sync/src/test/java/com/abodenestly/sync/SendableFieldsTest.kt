package com.abodenestly.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SendableFieldsTest {
    private val row: JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive("p1"),
            "household_id" to JsonPrimitive("h1"),
            "name_key" to JsonPrimitive("milk"),
            "price" to JsonPrimitive(1.2),
        ),
    )

    @Test
    fun `an item_price upsert keeps name_key and drops the other server-owned columns`() {
        assertEquals(setOf("name_key", "price"), sendableFields("item_price", row).keys)
    }

    @Test
    fun `a list_item upsert still drops name_key`() {
        assertEquals(setOf("price"), sendableFields("list_item", row).keys)
    }
}
