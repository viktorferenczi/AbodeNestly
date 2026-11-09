import Contract
import Foundation
import os

/// Hand-written fake for component and unit tests (TESTING §2: no mocking libraries).
/// Lives in the library, like web's `FakeAuthClient.ts`, so the app's test target can use it too.
public final class FakeAuthService: AuthService {
    public enum Call: Sendable, Equatable {
        case sendMagicLink(email: String, redirectTo: URL)
        case signInWithApple(idToken: String, nonce: String)
        case signInWithGoogle(redirectTo: URL)
        case handle(URL)
        case signOut
    }

    private struct State {
        var session: AuthSession?
        var calls: [Call] = []
        var sendMagicLinkError: AuthError?
        var appleError: AuthError?
        var googleError: AuthError?
        var profile: Result<Profile?, AuthError> = .success(nil)
        var memberships: Result<[Membership], AuthError> = .success([])
        var continuations: [AsyncStream<AuthSession?>.Continuation] = []
        var held: [CheckedContinuation<Void, Never>] = []
        var holdSend = false
    }

    private let state: OSAllocatedUnfairLock<State>

    public init(session: AuthSession? = nil) {
        state = OSAllocatedUnfairLock(initialState: State(session: session))
    }

    public var calls: [Call] { state.withLock { $0.calls } }

    public func setSendMagicLinkError(_ error: AuthError?) { state.withLock { $0.sendMagicLinkError = error } }
    public func setAppleError(_ error: AuthError?) { state.withLock { $0.appleError = error } }
    public func setGoogleError(_ error: AuthError?) { state.withLock { $0.googleError = error } }
    public func setProfile(_ result: Result<Profile?, AuthError>) { state.withLock { $0.profile = result } }
    public func setMemberships(_ result: Result<[Membership], AuthError>) { state.withLock { $0.memberships = result } }

    /// Pushes a session change to every `sessionChanges()` listener.
    public func emit(_ session: AuthSession?) {
        let listeners = state.withLock { state -> [AsyncStream<AuthSession?>.Continuation] in
            state.session = session
            return state.continuations
        }
        for listener in listeners { listener.yield(session) }
    }

    /// Makes `sendMagicLink` suspend until `releaseSendMagicLink()`, so tests can observe the `sending` state.
    public func holdSendMagicLink() { state.withLock { $0.holdSend = true } }

    public func releaseSendMagicLink() {
        let waiting = state.withLock { state -> [CheckedContinuation<Void, Never>] in
            state.holdSend = false
            defer { state.held.removeAll() }
            return state.held
        }
        waiting.forEach { $0.resume() }
    }

    private func record(_ call: Call) { state.withLock { $0.calls.append(call) } }

    public func currentSession() async -> AuthSession? { state.withLock { $0.session } }

    public func sessionChanges() -> AsyncStream<AuthSession?> {
        let (stream, continuation) = AsyncStream<AuthSession?>.makeStream()
        state.withLock { $0.continuations.append(continuation) }
        return stream
    }

    public func sendMagicLink(email: String, redirectTo: URL) async throws(AuthError) {
        record(.sendMagicLink(email: email, redirectTo: redirectTo))
        if state.withLock({ $0.holdSend }) {
            await withCheckedContinuation { continuation in
                state.withLock { $0.held.append(continuation) }
            }
        }
        if let error = state.withLock({ $0.sendMagicLinkError }) { throw error }
    }

    public func signInWithApple(idToken: String, nonce: String) async throws(AuthError) {
        record(.signInWithApple(idToken: idToken, nonce: nonce))
        if let error = state.withLock({ $0.appleError }) { throw error }
    }

    public func signInWithGoogle(redirectTo: URL) async throws(AuthError) {
        record(.signInWithGoogle(redirectTo: redirectTo))
        if let error = state.withLock({ $0.googleError }) { throw error }
    }

    public func handle(url: URL) async { record(.handle(url)) }

    public func signOut() async {
        record(.signOut)
        emit(nil)
    }

    public func fetchProfile(userId: UUID) async -> Result<Profile?, AuthError> { state.withLock { $0.profile } }

    public func fetchMemberships(userId: UUID) async -> Result<[Membership], AuthError> {
        state.withLock { $0.memberships }
    }
}
