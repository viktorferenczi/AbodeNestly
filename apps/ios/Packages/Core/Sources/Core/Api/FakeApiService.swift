import Contract
import Foundation
import os

/// Hand-written fake for component and unit tests (TESTING §2: no mocking libraries).
public final class FakeApiService: ApiService {
    public enum Call: Sendable, Equatable {
        case createHousehold(name: String)
        case acceptInvite(code: String)
    }

    private struct State {
        var calls: [Call] = []
        var createResult: Result<Household, ApiFailure> = .failure(.unknown)
        var acceptResult: Result<UUID, ApiFailure> = .failure(.unknown)
        var held: [CheckedContinuation<Void, Never>] = []
        var hold = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    public init() {}

    public var calls: [Call] { state.withLock { $0.calls } }

    public func setCreateResult(_ result: Result<Household, ApiFailure>) { state.withLock { $0.createResult = result } }
    public func setAcceptResult(_ result: Result<UUID, ApiFailure>) { state.withLock { $0.acceptResult = result } }

    /// Makes calls suspend until `release()`, so tests can observe `creating` / `joining`.
    public func hold() { state.withLock { $0.hold = true } }

    public func release() {
        let waiting = state.withLock { state -> [CheckedContinuation<Void, Never>] in
            state.hold = false
            defer { state.held.removeAll() }
            return state.held
        }
        waiting.forEach { $0.resume() }
    }

    private func suspendIfHeld() async {
        // Decide under the same lock `release` drains, so a release that wins the race resumes us.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let hold = state.withLock { state -> Bool in
                if state.hold { state.held.append(continuation) }
                return state.hold
            }
            if !hold { continuation.resume() }
        }
    }

    public func createHousehold(name: String) async -> Result<Household, ApiFailure> {
        state.withLock { $0.calls.append(.createHousehold(name: name)) }
        await suspendIfHeld()
        return state.withLock { $0.createResult }
    }

    public func acceptInvite(code: String) async -> Result<UUID, ApiFailure> {
        state.withLock { $0.calls.append(.acceptInvite(code: code)) }
        await suspendIfHeld()
        return state.withLock { $0.acceptResult }
    }
}
