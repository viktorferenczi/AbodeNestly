import Contract
import Foundation
import Sync
import os

/// SHOP-10 pure helpers. Web twin: `web/src/shopping/Barcode.ts`; keep the rules in step.
public enum Barcode {
    public static let codeError = "Enter 8 to 14 digits"

    /// Same rule as the API (`api/_lib/products.ts`): 8 to 14 digits, no check-digit test.
    public static func isBarcode(_ code: String) -> Bool {
        (8...14).contains(code.count) && code.allSatisfy { ("0"..."9").contains($0) }
    }

    /// A UPC-A code arrives from the camera as EAN-13 with a leading 0. Dropping it gives the 12-digit form that Open
    /// Food Facts and the web client store.
    public static func normalize(_ raw: String) -> String {
        raw.count == 13 && raw.hasPrefix("0") ? String(raw.dropFirst()) : raw
    }

    /// The product image is shown only for an https URL.
    public static func imageURL(_ string: String?) -> URL? {
        guard let string, let url = URL(string: string), url.scheme?.lowercased() == "https", url.host != nil else { return nil }
        return url
    }
}

/// A name typed for a code the API does not know (DESIGN C7 not-found).
public struct NameEntry: Codable, Equatable, Sendable {
    public var code: String
    public var name: String

    public init(code: String, name: String) {
        self.code = code
        self.name = name
    }
}

/// A lookup queued while offline: the item carries the code as its name until the lookup resolves.
public struct PendingLookup: Codable, Equatable, Sendable {
    public var householdId: UUID
    public var itemId: UUID
    public var code: String

    public init(householdId: UUID, itemId: UUID, code: String) {
        self.householdId = householdId
        self.itemId = itemId
        self.code = code
    }
}

/// The per-device name cache and the lookup queue, in `UserDefaults` (web: `localStorage`). A value is a `Sendable`
/// struct holding only a suite name, so tests pass their own suite and the app uses the standard defaults.
public struct BarcodeStore: Sendable {
    public static let namesKey = "abodenestly.barcodeNames"
    public static let lookupsKey = "abodenestly.barcodeLookups"
    public static let namesCap = 200

    /// One lock for the process: the scanner model and the drainer both read-modify-write the same keys.
    private static let lock = OSAllocatedUnfairLock(initialState: ())

    private let suite: String?

    public init(suite: String? = nil) {
        self.suite = suite
    }

    private var defaults: UserDefaults { suite.flatMap { UserDefaults(suiteName: $0) } ?? .standard }

    /// Decodes entry by entry so one bad entry does not drop the rest.
    private struct Lossy<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }

    private func load<T: Decodable>(_ key: String, as type: T.Type) -> [T] {
        guard let data = defaults.data(forKey: key), let list = try? JSONDecoder().decode([Lossy<T>].self, from: data) else { return [] }
        return list.compactMap(\.value)
    }

    private func save<T: Encodable>(_ list: [T], key: String) {
        // The cache and the queue are conveniences; the list item itself is already stored.
        if let data = try? JSONEncoder().encode(list) { defaults.set(data, forKey: key) }
    }

    // MARK: names

    /// An ordered array, not a dictionary, so eviction follows insertion order whatever the codes look like.
    public func names() -> [NameEntry] {
        Self.lock.withLock { _ in load(Self.namesKey, as: NameEntry.self) }
    }

    public func cachedName(_ code: String) -> String? {
        names().first { $0.code == code }?.name
    }

    /// Remembers `code → name` on this device, newest last; the oldest entries beyond `namesCap` are dropped.
    public func cacheName(code: String, name: String) {
        Self.lock.withLock { _ in
            var list = load(Self.namesKey, as: NameEntry.self).filter { $0.code != code }
            list.append(NameEntry(code: code, name: name))
            save(Array(list.suffix(Self.namesCap)), key: Self.namesKey)
        }
    }

    // MARK: queue

    public func pending() -> [PendingLookup] {
        Self.lock.withLock { _ in load(Self.lookupsKey, as: PendingLookup.self) }
    }

    public func appendPending(_ entry: PendingLookup) {
        Self.lock.withLock { _ in
            save(load(Self.lookupsKey, as: PendingLookup.self) + [entry], key: Self.lookupsKey)
        }
    }

    /// Re-reads the queue first, since it may have grown while a lookup was in flight.
    public func removePending(householdId: UUID, itemId: UUID) {
        Self.lock.withLock { _ in
            let kept = load(Self.lookupsKey, as: PendingLookup.self).filter { !($0.householdId == householdId && $0.itemId == itemId) }
            save(kept, key: Self.lookupsKey)
        }
    }
}

public enum BarcodeDrainError: Error, Sendable {
    /// The household whose queue is draining is no longer the open one (a switch during the pass).
    case householdClosed
}

/// Resolves the queued lookups. Web twin: `drainLookups`.
///
/// A product renames the item and sets its barcode, but only while the item still exists and is still named after the
/// code, so a rename by the person wins. A 404 or any other non-transient result drops the entry; a network failure or
/// 502 keeps it for the next drain. Entries of other households wait until that household is open.
/// A call during a pass is remembered (the latest one wins) and runs as one more pass when the current one ends, even if
/// it threw (a household switch closes the database). For the same household that pass skips the entries that just
/// failed transiently, so they are not fetched twice back to back.
public actor BarcodeLookupDrainer {
    public static let shared = BarcodeLookupDrainer()

    public struct Request: Sendable {
        let id = UUID()
        let database: HouseholdDatabase
        let householdId: UUID
        let lookup: @Sendable (String) async -> ProductLookupResult
        let isOnline: @MainActor @Sendable () -> Bool
        let store: BarcodeStore
        /// Called after a pass renamed at least one item, so the change is pushed.
        let onRenamed: @Sendable () async -> Void

        public init(
            database: HouseholdDatabase, householdId: UUID, lookup: @escaping @Sendable (String) async -> ProductLookupResult,
            isOnline: @escaping @MainActor @Sendable () -> Bool, store: BarcodeStore = BarcodeStore(),
            onRenamed: @escaping @Sendable () async -> Void = {}
        ) {
            self.database = database
            self.householdId = householdId
            self.lookup = lookup
            self.isOnline = isOnline
            self.store = store
            self.onRenamed = onRenamed
        }
    }

    private var running = false
    private var latest: Request?

    public init() {}

    public func drain(_ request: Request) async throws {
        if running {
            latest = request
            return
        }
        running = true
        var failure: (any Error)?
        var current: Request? = request
        var skip = Set<UUID>()
        while let pass = current {
            latest = nil
            var failedNow = Set<UUID>()
            do {
                failedNow = try await run(pass, skip: skip)
            } catch {
                if pass.id == request.id { failure = error }
            }
            // A call during the pass may have set it.
            let next = latest
            skip = next?.householdId == pass.householdId ? failedNow : []
            current = next
        }
        running = false
        latest = nil
        if let failure { throw failure }
    }

    /// One pass over the queue; returns the item ids that failed transiently.
    private func run(_ request: Request, skip: Set<UUID>) async throws -> Set<UUID> {
        var failedNow = Set<UUID>()
        guard await request.isOnline() else { return failedNow }
        guard await request.database.current == request.householdId else { throw BarcodeDrainError.householdClosed }
        var renamed = false
        let mine = request.store.pending().filter { $0.householdId == request.householdId && !skip.contains($0.itemId) }
        for entry in mine {
            let result = await request.lookup(entry.code)
            // Keep the entry only for failures that can pass; anything else would be retried forever.
            if result == .offline {
                failedNow.insert(entry.itemId)
                continue
            }
            if case .found(let product) = result {
                // A switch during the lookup must not read the other household's file as "no such item".
                guard await request.database.current == request.householdId else { throw BarcodeDrainError.householdClosed }
                if let item = try await request.database.listItemIdentity(id: entry.itemId), item.deletedAt == nil, item.name == entry.code {
                    try await request.database.patch(
                        table: "list_item", id: entry.itemId,
                        fields: ["name": .string(product.name), "barcode": .string(product.barcode)],
                        localOnly: ["name_key": .string(NameKey.make(product.name))]
                    )
                    renamed = true
                }
            }
            request.store.removePending(householdId: entry.householdId, itemId: entry.itemId)
        }
        if renamed { await request.onRenamed() }
        return failedNow
    }
}
