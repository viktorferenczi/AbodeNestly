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
        case updateProfile(userId: UUID, edit: ProfileEdit)
        case uploadAvatar(path: String, byteCount: Int)
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
        var token: String? = "fake-token"
        var tokenRequests: [Bool] = []
        var updateProfileResult: Result<Void, AuthError> = .success(())
        var uploadResult: Result<Void, AuthError> = .success(())
        var holdUpload = false
        var heldUploads: [CheckedContinuation<Void, Never>] = []
        var avatarURL: URL?
    }

    private let state: OSAllocatedUnfairLock<State>

    public init(session: AuthSession? = nil) {
        state = OSAllocatedUnfairLock(initialState: State(session: session))
    }

    public var calls: [Call] { state.withLock { $0.calls } }
    /// The `refresh` flag of every `accessToken` request, in order.
    public var tokenRequests: [Bool] { state.withLock { $0.tokenRequests } }
    public func setAccessToken(_ token: String?) { state.withLock { $0.token = token } }

    public func setSendMagicLinkError(_ error: AuthError?) { state.withLock { $0.sendMagicLinkError = error } }
    public func setAppleError(_ error: AuthError?) { state.withLock { $0.appleError = error } }
    public func setGoogleError(_ error: AuthError?) { state.withLock { $0.googleError = error } }
    public func setProfile(_ result: Result<Profile?, AuthError>) { state.withLock { $0.profile = result } }
    public func setMemberships(_ result: Result<[Membership], AuthError>) { state.withLock { $0.memberships = result } }

    public func setUpdateProfileResult(_ result: Result<Void, AuthError>) { state.withLock { $0.updateProfileResult = result } }
    public func setUploadResult(_ result: Result<Void, AuthError>) { state.withLock { $0.uploadResult = result } }
    public func setAvatarURL(_ url: URL?) { state.withLock { $0.avatarURL = url } }

    /// Makes `uploadAvatar` suspend until `releaseUpload()`, so tests can observe the uploading state.
    public func holdUpload() { state.withLock { $0.holdUpload = true } }

    public func releaseUpload() {
        let waiting = state.withLock { state -> [CheckedContinuation<Void, Never>] in
            state.holdUpload = false
            defer { state.heldUploads.removeAll() }
            return state.heldUploads
        }
        waiting.forEach { $0.resume() }
    }

    /// Pushes a session change to every `sessionChanges()` listener.
    public func emit(_ session: AuthSession?) {
        let listeners = state.withLock { state -> [AsyncStream<AuthSession?>.Continuation] in
            state.session = session
            return state.continuations
        }
        for listener in listeners { listener.yield(session) }
    }

    /// Makes the sign-in calls suspend until `releaseSignIn()`, so tests can observe the `sending` state.
    public func holdSignIn() { state.withLock { $0.holdSend = true } }

    public func releaseSignIn() {
        let waiting = state.withLock { state -> [CheckedContinuation<Void, Never>] in
            state.holdSend = false
            defer { state.held.removeAll() }
            return state.held
        }
        waiting.forEach { $0.resume() }
    }

    private func suspendIfHeld() async {
        // Decide under the same lock `releaseSignIn` drains, so a release that wins the race resumes us.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let hold = state.withLock { state -> Bool in
                if state.holdSend { state.held.append(continuation) }
                return state.holdSend
            }
            if !hold { continuation.resume() }
        }
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
        await suspendIfHeld()
        if let error = state.withLock({ $0.sendMagicLinkError }) { throw error }
    }

    public func signInWithApple(idToken: String, nonce: String) async throws(AuthError) {
        record(.signInWithApple(idToken: idToken, nonce: nonce))
        await suspendIfHeld()
        if let error = state.withLock({ $0.appleError }) { throw error }
    }

    public func signInWithGoogle(redirectTo: URL) async throws(AuthError) {
        record(.signInWithGoogle(redirectTo: redirectTo))
        await suspendIfHeld()
        if let error = state.withLock({ $0.googleError }) { throw error }
    }

    public func handle(url: URL) async { record(.handle(url)) }

    public func signOut() async {
        record(.signOut)
        emit(nil)
    }

    public func accessToken(refresh: Bool) async -> String? {
        state.withLock { state in
            state.tokenRequests.append(refresh)
            return state.token
        }
    }

    public func fetchProfile(userId: UUID) async -> Result<Profile?, AuthError> { state.withLock { $0.profile } }

    public func fetchMemberships(userId: UUID) async -> Result<[Membership], AuthError> {
        state.withLock { $0.memberships }
    }

    public func updateProfile(userId: UUID, edit: ProfileEdit) async -> Result<Void, AuthError> {
        record(.updateProfile(userId: userId, edit: edit))
        return state.withLock { $0.updateProfileResult }
    }

    public func uploadAvatar(path: String, data: Data) async -> Result<Void, AuthError> {
        record(.uploadAvatar(path: path, byteCount: data.count))
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let hold = state.withLock { state -> Bool in
                if state.holdUpload { state.heldUploads.append(continuation) }
                return state.holdUpload
            }
            if !hold { continuation.resume() }
        }
        return state.withLock { $0.uploadResult }
    }

    public func avatarURL(path: String) async -> URL? { state.withLock { $0.avatarURL } }
}
