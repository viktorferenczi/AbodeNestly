package com.abodenestly.sync

import androidx.room.ColumnInfo

/**
 * One live store on the C4 Stores screen (SHOP-05). [learned] is the number of `store_item_order` rows of this store,
 * i.e. items whose aisle position was learned for it. Local projection, not a contract entity (same as [StoreRecord]).
 */
data class StoreRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "learned") val learned: Int,
)
