import Foundation
import Sync

/// Hand-written fake (TESTING §2): records how often a screen asked for a sync and what the sheet asked for.
public final class FakeSync: SyncEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var _kicks = 0
    private var _retried: [Int] = []
    private var _discarded: [Int] = []
    private var _phase: SyncPhase = .idle
    private var continuations: [UUID: AsyncStream<SyncPhase>.Continuation] = [:]

    public init() {}

    public var kicks: Int { lock.withLock { _kicks } }
    public var retried: [Int] { lock.withLock { _retried } }
    public var discarded: [Int] { lock.withLock { _discarded } }

    public func kick() async { lock.withLock { _kicks += 1 } }
    public func retry(seq: Int) async { lock.withLock { _retried.append(seq) } }
    public func discard(seq: Int) async { lock.withLock { _discarded.append(seq) } }
    public var currentPhase: SyncPhase { get async { lock.withLock { _phase } } }

    public func phases() async -> AsyncStream<SyncPhase> {
        let (stream, continuation) = AsyncStream.makeStream(of: SyncPhase.self)
        let key = UUID()
        lock.withLock {
            continuations[key] = continuation
            continuation.yield(_phase)
        }
        continuation.onTermination = { [weak self] _ in self?.lock.withLock { self?.continuations[key] = nil } }
        return stream
    }

    public func setPhase(_ phase: SyncPhase) {
        let listeners = lock.withLock { () -> [AsyncStream<SyncPhase>.Continuation] in
            _phase = phase
            return Array(continuations.values)
        }
        listeners.forEach { $0.yield(phase) }
    }
}
