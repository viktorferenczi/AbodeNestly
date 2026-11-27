import Foundation

/// Hand-written fake (TESTING §2): records what was subscribed and lets a test fire events and status changes.
public final class FakeRealtime: RealtimeSubscribing, @unchecked Sendable {
    public struct Subscription: Sendable, Equatable {
        public let householdId: UUID
        public let tables: [String]
    }

    public enum Status: Sendable { case subscribed, error, timedOut, closed }

    private let lock = NSLock()
    private var _subscriptions: [Subscription] = []
    private var _cancelled = 0
    private var handlers: [Int: @Sendable () -> Void] = [:]
    private var nextKey = 0

    public init() {}

    /// Every `subscribe` call, oldest first.
    public var subscriptions: [Subscription] { lock.withLock { _subscriptions } }
    public var cancelled: Int { lock.withLock { _cancelled } }
    /// Subscriptions that have not been cancelled.
    public var active: Int { lock.withLock { handlers.count } }

    public func subscribe(householdId: UUID, tables: [String], onChange: @escaping @Sendable () -> Void) async -> RealtimeSubscription {
        let key = lock.withLock { () -> Int in
            _subscriptions.append(Subscription(householdId: householdId, tables: tables))
            nextKey += 1
            handlers[nextKey] = onChange
            return nextKey
        }
        return RealtimeSubscription { [weak self] in
            self?.lock.withLock {
                if self?.handlers.removeValue(forKey: key) != nil { self?._cancelled += 1 }
            }
        }
    }

    /// A postgres change on `table`. There is no payload to pass: the real service never reads it either.
    public func emit(table: String = "list_item") {
        let live = lock.withLock { Array(handlers.values) }
        live.forEach { $0() }
    }

    /// A channel status, as the live service treats it: joined catches up with one change; failures are swallowed.
    public func emit(status: Status) {
        if status == .subscribed { emit() }
    }
}
