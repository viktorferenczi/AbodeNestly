import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)
private let gb = Locale(identifier: "en_GB")

/// A money amount from its decimal text. A bare literal like `1.2` would go through a binary Double.
func dec(_ text: String) -> Decimal {
    Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")) ?? 0
}

/// The store the price tests shop at.
let priceStoreId = UUID(uuidString: "11111111-1111-4111-8111-111111111111") ?? UUID()

/// A store and a list that points at it.
func seedPriceList(_ db: HouseholdDatabase, list: UUID, store: UUID = priceStoreId, storeName: String = "Test Market") async throws {
    try await db.upsert(table: "store", row: storeRow(store, name: storeName))
    try await seedList(db, id: list, name: "Groceries", store: store)
}

/// One `item_price` observation, written the way a synced row arrives.
func seedPrice(
    _ db: HouseholdDatabase, store: UUID = priceStoreId, key: String, price: Decimal, seenAt: String,
    created: String = "2026-11-01T10:00:00.000Z", deleted: Bool = false
) async throws {
    try await db.upsert(table: "item_price", row: [
        "id": .string(UUID().uuidString.lowercased()), "store_id": .string(store.uuidString.lowercased()),
        "name_key": .string(key), "price": .decimal(price), "seen_at": .string(seenAt),
        "deleted_at": deleted ? .string("2026-11-02T10:00:00.000Z") : .null,
        "created_at": .string(created), "updated_at": .string(created), "sync_seq": .int(1),
    ])
}

/// The `item_price` outbox rows, oldest first.
func priceOutbox(_ db: HouseholdDatabase) async throws -> [(op: String, rowId: String, data: String)] {
    try await db.outboxForTesting().filter { $0.table == "item_price" }.map { ($0.op, $0.rowId, $0.data) }
}

func priceData(_ json: String) throws -> [String: JSONValue] {
    try JSONDecoder().decode([String: JSONValue].self, from: Data(json.utf8))
}

private func record(
    store: UUID = priceStoreId, key: String = "milk", price: Decimal, seenAt: String, created: String? = nil,
    id: UUID = UUID()
) -> ItemPriceRecord {
    ItemPriceRecord(id: id, storeId: store, nameKey: key, price: price, seenAt: seenAt, createdAt: created)
}

private let parseCases: [(String, PriceParse)] = [
    ("", .empty), ("   ", .empty), ("1.20", .value(dec("1.2"))), ("1,5", .value(dec("1.5"))), ("0", .value(dec("0"))), (" 3 ", .value(dec("3"))),
    ("12", .value(dec("12"))), ("0.99", .value(dec("0.99"))),
    ("-1", .invalid), ("1.234", .invalid), ("abc", .invalid), ("1.", .invalid), (".5", .invalid), ("1,2,3", .invalid),
    ("1.2.3", .invalid), ("€1", .invalid),
]

@Suite("SHOP-12 price logic")
struct PriceLogicTests {
    @Test("SHOP-12: parse accepts blank, a comma and at most 2 decimals, and rejects the rest", arguments: parseCases)
    func parse(text: String, expected: PriceParse) {
        #expect(Price.parse(text) == expected)
    }

    @Test("SHOP-12: lastPrice takes the newest seen_at, then the newest created_at, then the id")
    func newest() {
        let old = record(price: dec("1.0"), seenAt: "2026-11-01T10:00:00.000Z")
        let new = record(price: dec("2.0"), seenAt: "2026-11-03T10:00:00.000Z")
        #expect(Price.lastPrice(prices: [old, new], storeId: priceStoreId, key: "milk")?.price == dec("2.0"))
        #expect(Price.lastPrice(prices: [new, old], storeId: priceStoreId, key: "milk")?.price == dec("2.0"))

        let early = record(price: dec("1.0"), seenAt: "2026-11-03T10:00:00.000Z", created: "2026-11-03T10:00:01.000Z")
        let late = record(price: dec("2.0"), seenAt: "2026-11-03T10:00:00.000Z", created: "2026-11-03T10:00:02.000Z")
        #expect(Price.lastPrice(prices: [late, early], storeId: priceStoreId, key: "milk")?.price == dec("2.0"))

        let low = record(price: dec("1.0"), seenAt: "2026-11-03T10:00:00.000Z", created: "x", id: UUID(uuidString: "00000000-0000-4000-8000-000000000001") ?? UUID())
        let high = record(price: dec("2.0"), seenAt: "2026-11-03T10:00:00.000Z", created: "x", id: UUID(uuidString: "00000000-0000-4000-8000-000000000002") ?? UUID())
        #expect(Price.lastPrice(prices: [high, low], storeId: priceStoreId, key: "milk")?.price == dec("2.0"))
    }

    @Test("SHOP-12: lastPrice answers for its store and name only; lastPriceAnywhere ignores the store")
    func scope() {
        let other = UUID()
        let here = record(price: dec("1.0"), seenAt: "2026-11-01T10:00:00.000Z")
        let there = record(store: other, price: dec("3.0"), seenAt: "2026-11-05T10:00:00.000Z")
        let bread = record(key: "bread", price: dec("9.0"), seenAt: "2026-11-06T10:00:00.000Z")
        #expect(Price.lastPrice(prices: [here, there, bread], storeId: priceStoreId, key: "milk")?.price == dec("1.0"))
        #expect(Price.lastPriceAnywhere(prices: [here, there, bread], key: "milk")?.price == dec("3.0"))
        #expect(Price.lastPrice(prices: [], storeId: priceStoreId, key: "milk") == nil)
        #expect(Price.lastPriceAnywhere(prices: [here], key: "eggs") == nil)
    }

    @Test("SHOP-12: lookup uses the list's store first, then any store, and normalises the typed name")
    func lookup() {
        let other = UUID()
        let stores = [StoreRecord(id: priceStoreId, name: "Test Market"), StoreRecord(id: other, name: "Corner Shop")]
        let prices = [
            record(price: dec("1.0"), seenAt: "2026-11-01T10:00:00.000Z"),
            record(store: other, price: dec("3.0"), seenAt: "2026-11-05T10:00:00.000Z"),
        ]
        let atStore = Price.lastBought(name: "  MILK ", prices: prices, stores: stores, listStoreId: priceStoreId)
        #expect(atStore == LastBought(price: dec("1.0"), seenAt: "2026-11-01T10:00:00.000Z", storeName: "Test Market"))
        let anywhere = Price.lastBought(name: "milk", prices: prices, stores: stores, listStoreId: nil)
        #expect(anywhere == LastBought(price: dec("3.0"), seenAt: "2026-11-05T10:00:00.000Z", storeName: "Corner Shop"))
        #expect(Price.lastBought(name: "   ", prices: prices, stores: stores, listStoreId: nil) == nil)
        #expect(Price.lastBought(name: "eggs", prices: prices, stores: stores, listStoreId: nil) == nil)
    }

    @Test("SHOP-12: AC2 the caption is the price and the store name; a deleted store leaves just the price")
    func caption() {
        let last = LastBought(price: dec("1.2"), seenAt: "2026-11-03T12:00:00.000Z", storeName: "Test Market")
        #expect(Price.caption(last, currency: "EUR", locale: gb) == "€1.20 · Test Market")
        var gone = last
        gone.storeName = nil
        #expect(Price.caption(gone, currency: "EUR", locale: gb) == "€1.20")
        #expect(Price.caption(last, currency: "GBP", locale: gb) == "£1.20 · Test Market")
        // The store is no longer live: the lookup finds the price and leaves the name out.
        let deleted = Price.lastBought(
            name: "milk", prices: [record(price: dec("1.2"), seenAt: "2026-11-03T12:00:00.000Z")], stores: [], listStoreId: nil
        )
        #expect(deleted?.storeName == nil)
    }

    @Test("SHOP-12: the Last bought line has the day, the price and the store")
    func line() {
        let last = LastBought(price: dec("1.2"), seenAt: "2026-11-03T12:00:00.000Z", storeName: "Test Market")
        #expect(Price.lastBoughtLine(last, currency: "EUR", locale: gb, timeZone: .gmt) == "Last bought: 3 Nov, €1.20 at Test Market")
        var gone = last
        gone.storeName = nil
        #expect(Price.lastBoughtLine(gone, currency: "EUR", locale: gb, timeZone: .gmt) == "Last bought: 3 Nov, €1.20")
    }

    @Test("SHOP-12: the sheet form turns the price text into a price; a bad price blocks the save")
    func formPrice() {
        #expect(ItemSheetLogic.fields(ItemForm(name: "Milk", priceText: "1,5"))?.price == dec("1.5"))
        #expect(ItemSheetLogic.fields(ItemForm(name: "Milk"))?.price == nil)
        #expect(ItemSheetLogic.fields(ItemForm(name: "Milk", priceText: "abc")) == nil)
        #expect(ItemSheetLogic.priceInvalid(ItemForm(name: "Milk", priceText: "-1")))
        #expect(!ItemSheetLogic.priceInvalid(ItemForm(name: "Milk", priceText: "")))
    }

    @Test("SHOP-12: the price row carries the store, the normalised name_key, the decimal price and seen_at")
    func row() {
        let id = UUID()
        let household = UUID()
        let row = Price.row(id: id, householdId: household, storeId: priceStoreId, name: "  Whole  Milk", price: dec("1.2"), now: fixedNow)
        #expect(row["name_key"] == .string("whole milk"))
        #expect(row["store_id"] == .string(priceStoreId.uuidString.lowercased()))
        #expect(row["price"] == .decimal(dec("1.2")))
        #expect(row["seen_at"] == .string("2027-01-15T08:00:00.000Z"))
        #expect(row["id"] == .string(id.uuidString.lowercased()))
    }

    @Test("SHOP-12: item_price is pushed, like the other shopping tables")
    func pushable() {
        #expect(SyncTables.pushable.contains("item_price"))
    }
}

@MainActor
@Suite("SHOP-12 price writes", .serialized)
struct PriceWriteTests {
    private func harness(withStore: Bool = true, makeId: @escaping @MainActor () -> UUID = { UUID() }) async throws -> DetailHarness {
        let h = try await DetailHarness(seed: { db, list in
            if withStore { try await seedPriceList(db, list: list) }
            try await seedItem(db, list: list, name: "Milk")
        }, makeId: makeId)
        await h.settle { h.model.unchecked.count == 1 && (!withStore || h.model.priceStore != nil) }
        return h
    }

    @Test("SHOP-12: AC1 a new item saved with a price queues one item_price upsert for that store and name")
    func newWithPrice() async throws {
        let h = try await harness()
        #expect(await h.model.create(ItemFields(name: "  Whole  Milk", price: dec("1.2"))))
        let queued = try await priceOutbox(h.db)
        #expect(queued.count == 1)
        #expect(queued.first?.op == "upsert")
        let data = try priceData(try #require(queued.first).data)
        #expect(data["store_id"] == .string(priceStoreId.uuidString.lowercased()))
        #expect(data["name_key"] == .string("whole milk"))
        #expect(data["price"] == .double(1.2))
        #expect(data["seen_at"] == .string("2027-01-15T08:00:00.000Z"))
        #expect(data["id"] == nil && data["household_id"] == nil)
        // The item rides in the same write, and sync is kicked once.
        #expect(try await h.outbox().count == 1)
        #expect(await h.kickCount == 1)
        let local = try await h.db.queryForTesting("SELECT price, name_key FROM item_price")
        #expect(local == [["price": "1.2", "name_key": "whole milk"]])
    }

    @Test("SHOP-12: AC1 an edit with a price writes the changed columns and the price together")
    func editWithPrice() async throws {
        let h = try await harness()
        let item = try #require(h.model.unchecked.first)
        #expect(await h.model.save(item, ItemFields(name: "Milk", quantity: 2, price: dec("0.99"))))
        let items = try await h.outbox()
        #expect(items.count == 1)
        #expect(try priceData(try #require(items.first).data).keys.sorted() == ["quantity"])
        let queued = try await priceOutbox(h.db)
        #expect(queued.count == 1)
        #expect(try priceData(try #require(queued.first).data)["price"] == .double(0.99))
        #expect(await h.kickCount == 1)
    }

    @Test("SHOP-12: 1.10 and 0.3 stay exact from the typed text to the local row and the queued data", arguments: [
        ("1.10", "1.1", "\"price\":1.1"), ("0,3", "0.3", "\"price\":0.3"), ("19.99", "19.99", "\"price\":19.99"),
    ])
    func exactRoundTrip(typed: String, stored: String, wire: String) async throws {
        let h = try await harness()
        let fields = try #require(ItemSheetLogic.fields(ItemForm(name: "Milk", priceText: typed)))
        #expect(fields.price == dec(stored))
        #expect(await h.model.create(fields))
        let local = try await h.db.queryForTesting("SELECT price FROM item_price")
        #expect(local == [["price": stored]])
        let queued = try await priceOutbox(h.db)
        let data = try #require(queued.first).data
        #expect(data.contains(wire + ",") || data.contains(wire + "}"))
        // Read back through the observation the caption uses: the same Decimal, not a nearby Double.
        await h.settle { h.model.priceSnapshot.prices.count == 1 }
        #expect(h.model.priceSnapshot.prices.first?.price == dec(stored))
    }

    @Test("SHOP-12: a price-only save writes the price and no item change")
    func priceOnly() async throws {
        let h = try await harness()
        let item = try #require(h.model.unchecked.first)
        #expect(await h.model.save(item, ItemFields(name: "Milk", price: dec("1.5"))))
        #expect(try await h.outbox().isEmpty)
        #expect(try await priceOutbox(h.db).count == 1)
        #expect(await h.kickCount == 1)
    }

    @Test("SHOP-12: an empty price writes nothing")
    func emptyPrice() async throws {
        let h = try await harness()
        let item = try #require(h.model.unchecked.first)
        #expect(await h.model.save(item, ItemFields(name: "Milk")))
        #expect(await h.model.create(ItemFields(name: "Eggs")))
        #expect(try await priceOutbox(h.db).isEmpty)
        #expect(try await h.db.queryForTesting("SELECT id FROM item_price").isEmpty)
    }

    @Test("SHOP-12: AC3 a list with no store offers no price and records none")
    func noStore() async throws {
        let h = try await harness(withStore: false)
        #expect(h.model.priceStore == nil)
        #expect(await h.model.create(ItemFields(name: "Eggs", price: dec("2"))))
        #expect(try await priceOutbox(h.db).isEmpty)
    }

    @Test("SHOP-12: a list pointing at a deleted store offers no price")
    func deletedStore() async throws {
        let h = try await harness()
        try await h.db.remove(table: "store", id: priceStoreId)
        await h.settle { h.model.priceStore == nil }
        #expect(h.model.priceStore == nil)
    }

    @Test("SHOP-12: each save keeps its own observation and the caption shows the newest")
    func newestWins() async throws {
        let h = try await harness()
        try await seedPrice(h.db, key: "milk", price: dec("1.0"), seenAt: "2026-11-01T10:00:00.000Z")
        try await seedPrice(h.db, key: "milk", price: dec("9.0"), seenAt: "2026-11-02T10:00:00.000Z", deleted: true)
        await h.settle { h.model.priceSnapshot.prices.count == 1 }
        let item = try #require(h.model.unchecked.first)
        #expect(h.model.priceCaption(for: item) == Price.caption(
            LastBought(price: dec("1.0"), seenAt: "2026-11-01T10:00:00.000Z", storeName: "Test Market"), currency: "EUR"
        ))
        #expect(await h.model.save(item, ItemFields(name: "Milk", price: dec("1.4"))))
        await h.settle { h.model.priceSnapshot.prices.count == 2 }
        #expect(h.model.lastBought("milk")?.price == dec("1.4"))
    }

    @Test("SHOP-12: the household currency comes from the local household row, EUR until then")
    func currency() async throws {
        let h = try await harness()
        #expect(h.model.priceSnapshot.currency == "EUR")
        try await h.db.upsert(table: "household", row: ["id": .string(h.household.uuidString.lowercased()), "currency": .string("GBP")])
        await h.settle { h.model.priceSnapshot.currency == "GBP" }
        #expect(h.model.priceSnapshot.currency == "GBP")
    }
}

@Suite("SHOP-12 migration v7")
struct PriceMigrationTests {
    @Test("SHOP-12: v7 creates item_price with its indexes, resets the cursor and keeps the rows of an older file")
    func createsTable() async throws {
        let root = switcherTempRoot()
        let household = UUID()
        let seeded = HouseholdDatabase(rootURL: root)
        try await seeded.openLegacyForTesting(householdId: household, upTo: "v6")

        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        #expect(try await db.readCursor() == 0)
        let columns = try await db.queryForTesting("SELECT name FROM pragma_table_info('item_price') ORDER BY cid").compactMap { $0["name"] }
        #expect(columns == [
            "id", "household_id", "store_id", "name_key", "price", "seen_at", "created_at", "updated_at", "deleted_at", "sync_seq",
        ])
        let indexes = try await db.queryForTesting("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'item_price' AND name LIKE 'item_price_%'")
        #expect(Set(indexes.compactMap { $0["name"] }) == ["item_price_store_key_idx", "item_price_household_idx"])
        let items = try await db.queryForTesting("SELECT name FROM list_item")
        #expect(items == [["name": "Milk"]])
    }

    @Test("SHOP-12: a pulled item_price row is stored")
    func pullStores() async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        let id = UUID().uuidString.lowercased()
        let row: [String: JSONValue] = [
            "id": .string(id), "household_id": .string(household.uuidString.lowercased()),
            "store_id": .string(priceStoreId.uuidString.lowercased()), "name_key": .string("milk"), "price": .double(1.2),
            "seen_at": .string("2026-11-03T12:00:00.123456+00:00"), "created_at": .string("2026-11-03T12:00:00Z"),
            "updated_at": .string("2026-11-03T12:00:00Z"), "deleted_at": .null, "sync_seq": .int(7),
        ]
        let applied = try await db.applyPull(householdId: household, page: SyncPullPage(cursor: 7, hasMore: false, changes: ["item_price": [row]]))
        #expect(applied)
        let stored = try await db.queryForTesting("SELECT seen_at, price FROM item_price")
        #expect(stored == [["seen_at": "2026-11-03T12:00:00.123Z", "price": "1.2"]])
    }
}
