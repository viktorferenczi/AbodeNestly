import Contract
import Foundation
import Testing
@testable import Sync

@Suite("SHOP-12: name_key is client-sent for item_price only")
struct SendableFieldsTests {
    private let row: [String: JSONValue] = [
        "id": .string("p1"), "household_id": .string("h1"), "name_key": .string("milk"), "price": .string("1.20"),
    ]

    @Test("an item_price upsert keeps name_key and drops the other server-owned columns")
    func itemPriceKeepsNameKey() {
        let sent: [String: JSONValue] = SyncTables.sendable(table: "item_price", fields: row)
        #expect(Set(sent.keys) == ["name_key", "price"])
    }

    @Test("a list_item upsert still drops name_key")
    func listItemDropsNameKey() {
        let sent: [String: JSONValue] = SyncTables.sendable(table: "list_item", fields: row)
        #expect(Set(sent.keys) == ["price"])
    }
}
