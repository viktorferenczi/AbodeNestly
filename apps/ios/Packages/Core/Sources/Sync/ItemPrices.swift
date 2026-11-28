import Foundation
import GRDB

/// A live `item_price` row as the last-price lookup reads it (SHOP-12): one observation of a name at a store.
public struct ItemPriceRecord: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var storeId: UUID
    /// The normalised item name (`NameKey`), as the server stores it.
    public var nameKey: String
    public var price: Double
    /// Millisecond UTC text.
    public var seenAt: String
    /// Millisecond UTC text, or `nil` for a row from before the server stamped it.
    public var createdAt: String?

    public init(id: UUID, storeId: UUID, nameKey: String, price: Double, seenAt: String, createdAt: String? = nil) {
        self.id = id
        self.storeId = storeId
        self.nameKey = nameKey
        self.price = price
        self.seenAt = seenAt
        self.createdAt = createdAt
    }
}

/// The household's live prices and the currency to show them in, read together so they never disagree.
public struct ItemPriceSnapshot: Sendable, Equatable {
    public var prices: [ItemPriceRecord]
    /// The household's ISO 4217 code (CORE-05), `EUR` (the server's default) until the household row has been pulled.
    public var currency: String

    public init(prices: [ItemPriceRecord] = [], currency: String = "EUR") {
        self.prices = prices
        self.currency = currency
    }
}

extension HouseholdDatabase {
    /// The household's live `item_price` rows and currency, re-emitted whenever either changes, so a price written
    /// offline shows at once. Finishes at once when the household is not open.
    public func observeItemPrices(householdId: UUID) -> AsyncStream<ItemPriceSnapshot> {
        guard let pool, handle?.householdId == householdId else { return AsyncStream { $0.finish() } }
        let key = householdId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db -> ItemPriceSnapshot in
            let rows = try Row.fetchAll(
                db,
                sql: """
                SELECT id, store_id, name_key, price, seen_at, created_at FROM item_price
                WHERE household_id = ? AND deleted_at IS NULL
                """,
                arguments: [key]
            )
            var prices: [ItemPriceRecord] = []
            for row in rows {
                let idText: String = row["id"]
                let storeText: String = row["store_id"]
                guard let id = UUID(uuidString: idText), let storeId = UUID(uuidString: storeText) else { continue }
                let nameKey: String = row["name_key"]
                let price: Double = row["price"]
                let seenAt: String = row["seen_at"]
                let createdAt: String? = row["created_at"]
                prices.append(ItemPriceRecord(id: id, storeId: storeId, nameKey: nameKey, price: price, seenAt: seenAt, createdAt: createdAt))
            }
            let currency: String? = try String.fetchOne(db, sql: "SELECT currency FROM household WHERE id = ?", arguments: [key])
            let code: String = (currency?.isEmpty == false ? currency : nil) ?? "EUR"
            return ItemPriceSnapshot(prices: prices, currency: code)
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await value in observation.values(in: pool) { continuation.yield(value) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last value.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
