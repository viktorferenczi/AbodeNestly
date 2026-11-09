import Foundation
import Observation

/// Same states as the web `SignIn` reducer (DESIGN B1).
public enum SignInState: Equatable, Sendable {
    case idle
    case sending
    case linkSent(secondsLeft: Int, resending: Bool, error: AuthError?)
    case error(AuthError)
}

@Observable
@MainActor
public final class SignInModel {
    public static let resendSeconds = 30

    public var email = ""
    public private(set) var state: SignInState = .idle

    @ObservationIgnored private let auth: any AuthService
    @ObservationIgnored private let redirectTo: URL
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var countdown: Task<Void, Never>?

    /// `sleep` is injected so tests advance the resend countdown without waiting.
    public init(
        auth: any AuthService,
        redirectTo: URL,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.auth = auth
        self.redirectTo = redirectTo
        self.sleep = sleep
    }

    public var isBusy: Bool { state == .sending }

    /// AC3: a request is only sent for a plausible address.
    static func isValid(email: String) -> Bool {
        email.range(of: #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#, options: .regularExpression) != nil
    }

    private var trimmedEmail: String { email.trimmingCharacters(in: .whitespacesAndNewlines) }

    public func submit() async {
        guard !isBusy else { return }
        guard Self.isValid(email: trimmedEmail) else { return state = .error(.invalidEmail) }
        state = .sending
        do {
            try await auth.sendMagicLink(email: trimmedEmail, redirectTo: redirectTo)
            enterLinkSent()
        } catch {
            state = .error(error)
        }
    }

    public func resend() async {
        guard case .linkSent(let secondsLeft, false, _) = state, secondsLeft == 0 else { return }
        state = .linkSent(secondsLeft: 0, resending: true, error: nil)
        do {
            try await auth.sendMagicLink(email: trimmedEmail, redirectTo: redirectTo)
            enterLinkSent()
        } catch {
            state = .linkSent(secondsLeft: 0, resending: false, error: error)
        }
    }

    public func signInWithApple(idToken: String, nonce: String) async {
        guard !isBusy else { return }
        state = .sending
        do {
            try await auth.signInWithApple(idToken: idToken, nonce: nonce)
            state = .idle
        } catch {
            fail(error)
        }
    }

    /// The Apple sheet reported a failure before an ID token existed.
    public func appleFailed(_ error: AuthError) {
        fail(error)
    }

    public func signInWithGoogle() async {
        guard !isBusy else { return }
        state = .sending
        do {
            try await auth.signInWithGoogle(redirectTo: redirectTo)
            state = .idle
        } catch {
            fail(error)
        }
    }

    /// Dismissing a provider sheet is not an error.
    private func fail(_ error: AuthError) {
        state = error == .cancelled ? .idle : .error(error)
    }

    private func enterLinkSent() {
        state = .linkSent(secondsLeft: Self.resendSeconds, resending: false, error: nil)
        countdown?.cancel()
        countdown = Task { [sleep] in
            while !Task.isCancelled {
                do { try await sleep(.seconds(1)) } catch { return }
                if !tick() { return }
            }
        }
    }

    /// Returns whether the countdown should keep running.
    private func tick() -> Bool {
        guard case .linkSent(let secondsLeft, let resending, let error) = state, secondsLeft > 0 else { return false }
        state = .linkSent(secondsLeft: secondsLeft - 1, resending: resending, error: error)
        return secondsLeft - 1 > 0
    }

    deinit { countdown?.cancel() }
}
