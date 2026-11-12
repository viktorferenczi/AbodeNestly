import Contract
import Foundation
import os

/// Hand-written fake for component and unit tests (TESTING §2: no mocking libraries).
public final class FakeApiService: ApiService {
    public enum Call: Sendable, Equatable {
        case createHousehold(name: String)
        case acceptInvite(code: String)
        case createInvite(householdId: UUID)
        case fetchLiveInvite(householdId: UUID)
        case retireInvite(id: UUID)
    }

    private struct State {
        var calls: [Call] = []
        var createResult: Result<Household, ApiFailure> = .failure(.unknown)
        var acceptResult: Result<UUID, ApiFailure> = .failure(.unknown)
        var createInviteResults: [Result<Invite, ApiFailure>] = []
        var createInviteResult: Result<Invite, ApiFailure> = .failure(.unknown)
        var liveInviteResult: Result<Invite?, ApiFailure> = .success(nil)
        var retireResult: Result<Void, ApiFailure> = .success(())
        var held: [CheckedContinuation<Void, Never>] = []
        var hold = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    public init() {}

    public var calls: [Call] { state.withLock { $0.calls } }

    public func setCreateResult(_ result: Result<Household, ApiFailure>) { state.withLock { $0.createResult = result } }
    public func setAcceptResult(_ result: Result<UUID, ApiFailure>) { state.withLock { $0.acceptResult = result } }

    /// Every `createInvite` returns this.
    public func setCreateInviteResult(_ result: Result<Invite, ApiFailure>) {
        state.withLock { $0.createInviteResult = result }
    }
    /// Queued results for `createInvite`, consumed in order before falling back to `setCreateInviteResult`.
    public func queueCreateInviteResult(_ result: Result<Invite, ApiFailure>) {
        state.withLock { $0.createInviteResults.append(result) }
    }
    public func setLiveInviteResult(_ result: Result<Invite?, ApiFailure>) {
        state.withLock { $0.liveInviteResult = result }
    }
    public func setRetireResult(_ result: Result<Void, ApiFailure>) { state.withLock { $0.retireResult = result } }

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

    public func createInvite(householdId: UUID) async -> Result<Invite, ApiFailure> {
        state.withLock { $0.calls.append(.createInvite(householdId: householdId)) }
        await suspendIfHeld()
        return state.withLock { state in
            state.createInviteResults.isEmpty ? state.createInviteResult : state.createInviteResults.removeFirst()
        }
    }

    public func fetchLiveInvite(householdId: UUID) async -> Result<Invite?, ApiFailure> {
        state.withLock { $0.calls.append(.fetchLiveInvite(householdId: householdId)) }
        await suspendIfHeld()
        return state.withLock { $0.liveInviteResult }
    }

    public func retireInvite(id: UUID) async -> Result<Void, ApiFailure> {
        state.withLock { $0.calls.append(.retireInvite(id: id)) }
        await suspendIfHeld()
        return state.withLock { $0.retireResult }
    }
}
