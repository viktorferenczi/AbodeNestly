import Contract
import Foundation
import Sync

/// What the price field holds: nothing, a valid amount, or text that is not a price.
public enum PriceParse: Equatable, Sendable {
    case empty
    case value(Decimal)
    case invalid
}

/// The newest observation that answers a lookup: what the row caption and the sheet line show.
public struct LastBought: Equatable, Sendable {
    public var price: Decimal
    /// Millisecond UTC text.
    public var seenAt: String
    /// The store's name while that store is live; `nil` once it is deleted.
    public var storeName: String?

    public init(price: Decimal, seenAt: String, storeName: String?) {
        self.price = price
        self.seenAt = seenAt
        self.storeName = storeName
    }
}

/// SHOP-12 logic, kept apart from the views so it is table-testable. Web twin: `web/src/shopping/Price.ts`.
public enum Price {
    /// Blank is "no price". Otherwise 0 or more with at most 2 decimals; a comma works as the decimal mark.
    public static func parse(_ text: String) -> PriceParse {
        let trimmed: String = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .empty }
        var whole = ""
        var fraction = ""
        var sawMark = false
        for character in trimmed {
            if character == "." || character == "," {
                if sawMark { return .invalid }
                sawMark = true
                continue
            }
            guard character.isASCII, character.isNumber else { return .invalid }
            if sawMark { fraction.append(character) } else { whole.append(character) }
        }
        if whole.isEmpty { return .invalid }
        if sawMark && (fraction.isEmpty || fraction.count > 2) { return .invalid }
        let canonical: String = sawMark ? whole + "." + fraction : whole
        // From the digit string, not a Double, so 1.10 and 0.3 stay exact.
        guard let value = Decimal(string: canonical, locale: Locale(identifier: "en_US_POSIX")) else { return .invalid }
        return .value(value)
    }

    /// The newest live observation of `key` at `storeId`: newest `seenAt`, ties to the newest `createdAt`, then id.
    public static func lastPrice(prices: [ItemPriceRecord], storeId: UUID, key: String) -> ItemPriceRecord? {
        var best: ItemPriceRecord?
        for candidate in prices where candidate.storeId == storeId && candidate.nameKey == key {
            if let current = best, !isNewer(candidate, than: current) { continue }
            best = candidate
        }
        return best
    }

    /// The newest live observation of `key` at any store.
    public static func lastPriceAnywhere(prices: [ItemPriceRecord], key: String) -> ItemPriceRecord? {
        var best: ItemPriceRecord?
        for candidate in prices where candidate.nameKey == key {
            if let current = best, !isNewer(candidate, than: current) { continue }
            best = candidate
        }
        return best
    }

    private static func isNewer(_ a: ItemPriceRecord, than b: ItemPriceRecord) -> Bool {
        if a.seenAt != b.seenAt { return a.seenAt > b.seenAt }
        let aCreated: String = a.createdAt ?? ""
        let bCreated: String = b.createdAt ?? ""
        if aCreated != bCreated { return aCreated > bCreated }
        return a.id.uuidString > b.id.uuidString
    }

    /// C2 rule: with a list store, the last price at that store; with none, the newest price at any store. The store's
    /// name is left out when that store is no longer among the live `stores`. `nil` for a blank name or no price.
    public static func lastBought(
        name: String, prices: [ItemPriceRecord], stores: [StoreRecord], listStoreId: UUID?
    ) -> LastBought? {
        let key: String = NameKey.make(name)
        if key.isEmpty { return nil }
        let found: ItemPriceRecord?
        if let listStoreId {
            found = lastPrice(prices: prices, storeId: listStoreId, key: key)
        } else {
            found = lastPriceAnywhere(prices: prices, key: key)
        }
        guard let found else { return nil }
        var storeName: String?
        for store in stores where store.id == found.storeId { storeName = store.name }
        return LastBought(price: found.price, seenAt: found.seenAt, storeName: storeName)
    }

    /// "€1.20" in the household currency.
    public static func formatMoney(_ amount: Decimal, currency: String, locale: Locale = .current) -> String {
        amount.formatted(.currency(code: currency).locale(locale))
    }

    /// C2 caption: "€1.20 · Test Market"; a deleted store leaves just the price.
    public static func caption(_ last: LastBought, currency: String, locale: Locale = .current) -> String {
        let money: String = formatMoney(last.price, currency: currency, locale: locale)
        guard let storeName = last.storeName else { return money }
        return money + " · " + storeName
    }

    /// C3 line: "Last bought: 3 Nov, €1.20 at Test Market".
    public static func lastBoughtLine(
        _ last: LastBought, currency: String, locale: Locale = .current, timeZone: TimeZone = .current
    ) -> String {
        let money: String = formatMoney(last.price, currency: currency, locale: locale)
        var line = "Last bought: "
        if let date = parse(seenAt: last.seenAt) {
            let style: Date.FormatStyle = Date.FormatStyle(locale: locale, timeZone: timeZone).day().month(.abbreviated)
            line += date.formatted(style) + ", "
        }
        line += money
        if let storeName = last.storeName { line += " at " + storeName }
        return line
    }

    private static func parse(seenAt text: String) -> Date? {
        if let date = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return date }
        return try? Date(text, strategy: Date.ISO8601FormatStyle())
    }

    /// One new observation (each price is kept, SPEC §8 ItemPrice): the row stored locally and queued. `name_key` is the
    /// one server-owned-looking column the client sends for this table.
    public static func row(
        id: UUID, householdId: UUID, storeId: UUID, name: String, price: Decimal, now: Date
    ) -> [String: JSONValue] {
        let stamp: String = now.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
        return [
            "id": .string(id.uuidString.lowercased()), "household_id": .string(householdId.uuidString.lowercased()),
            "store_id": .string(storeId.uuidString.lowercased()), "name_key": .string(NameKey.make(name)),
            "price": .decimal(price), "seen_at": .string(stamp), "deleted_at": .null,
            "created_at": .string(stamp), "updated_at": .string(stamp), "sync_seq": .int(0),
        ]
    }
}
