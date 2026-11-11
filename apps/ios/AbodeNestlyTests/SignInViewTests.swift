import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
import os
@testable import Core

/// Controls the resend countdown: each `advance()` completes exactly one pending one-second sleep.
final class ManualSleeper: Sendable {
    private let waiters = OSAllocatedUnfairLock<[CheckedContinuation<Void, any Error>]>(initialState: [])

    func sleep(_ duration: Duration) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                waiters.withLock { $0.append(continuation) }
            }
        } onCancel: {
            let pending = waiters.withLock { list -> [CheckedContinuation<Void, any Error>] in
                defer { list.removeAll() }
                return list
            }
            pending.forEach { $0.resume(throwing: CancellationError()) }
        }
    }

    @MainActor
    func advance() async {
        await waitFor { self.waiters.withLock { !$0.isEmpty } }
        waiters.withLock { $0.removeFirst() }.resume()
    }
}

/// Polls until `condition` holds; component tests drive `Task`s that views start from button taps.
@MainActor
func waitFor(_ condition: @MainActor () -> Bool, timeout: Duration = .seconds(5)) async {
    let deadline = ContinuousClock.now + timeout
    while !condition(), ContinuousClock.now < deadline {
        try? await Task.sleep(for: .milliseconds(5))
    }
}

private let callback = URL(string: "abodenestly-dev://auth/callback")!

@MainActor
private struct Harness {
    let auth = FakeAuthService()
    let sleeper = ManualSleeper()
    let model: SignInModel

    init() {
        model = SignInModel(auth: auth, redirectTo: callback, sleep: sleeper.sleep)
    }

    func view(online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try SignInView(model: model, isOnline: online).inspect()
    }

    func isDisabled(_ id: String, online: Bool = true) throws -> Bool {
        try view(online: online).find(viewWithAccessibilityIdentifier: id).isDisabled()
    }
}

@MainActor
@Suite("CORE-01 sign-in screen")
struct SignInViewTests {
    @Test("CORE-01: AC1 idle shows the form and provider buttons, enabled")
    func idle() throws {
        let h = Harness()
        let view = try h.view()
        _ = try view.find(text: "Sign in to AbodeNestly")
        _ = try view.find(viewWithAccessibilityIdentifier: "signin.email")
        _ = try view.find(text: "Send magic link")
        _ = try view.find(text: "or") // divider is hidden from VoiceOver but present
        _ = try view.find(text: "Sign in with Google")
        _ = try view.find(viewWithAccessibilityIdentifier: "signin.apple")
        #expect(try h.isDisabled("signin.send") == false)
        #expect(try h.isDisabled("signin.google") == false)
        #expect(throws: (any Error).self) { try view.find(viewWithAccessibilityIdentifier: "offline-banner") }
    }

    @Test("CORE-01: AC2 sending shows a spinner and disables the buttons")
    func sending() async throws {
        let h = Harness()
        h.model.email = "ada@test.local"
        h.auth.holdSignIn()
        let task = Task { await h.model.submit() }
        await waitFor { h.model.state == .sending }
        _ = try h.view().find(viewWithAccessibilityIdentifier: "signin.spinner")
        #expect(h.model.action == .magicLink)
        #expect(try h.isDisabled("signin.send"))
        #expect(try h.isDisabled("signin.google"))
        h.auth.releaseSignIn()
        await task.value
    }

    @Test("CORE-01: AC2 success replaces the form with link-sent; Resend is disabled for 30 ticks")
    func linkSentAndResendCountdown() async throws {
        let h = Harness()
        h.model.email = "ada@test.local"
        await h.model.submit()

        #expect(h.model.state == .linkSent(secondsLeft: 30, resending: false, error: nil))
        #expect(h.auth.calls == [.sendMagicLink(email: "ada@test.local", redirectTo: callback)])
        var view = try h.view()
        _ = try view.find(text: "Check your email")
        _ = try view.find(text: "We sent a sign-in link to ada@test.local.")
        #expect(throws: (any Error).self) { try view.find(viewWithAccessibilityIdentifier: "signin.email") }
        #expect(try h.isDisabled("signin.resend"))

        for second in 1...29 {
            await h.sleeper.advance()
            await waitFor { h.model.state == .linkSent(secondsLeft: 30 - second, resending: false, error: nil) }
        }
        #expect(try h.isDisabled("signin.resend"), "still disabled at 1 s left")
        await h.sleeper.advance()
        await waitFor { h.model.state == .linkSent(secondsLeft: 0, resending: false, error: nil) }
        view = try h.view()
        #expect(try h.isDisabled("signin.resend") == false)
        _ = try view.find(button: "Resend")
    }

    @Test("CORE-01: AC2 Resend sends a second link and restarts the countdown")
    func resend() async throws {
        let h = Harness()
        h.model.email = "ada@test.local"
        await h.model.submit()
        for _ in 1...30 { await h.sleeper.advance() }
        await waitFor { h.model.state == .linkSent(secondsLeft: 0, resending: false, error: nil) }

        try h.view().find(viewWithAccessibilityIdentifier: "signin.resend").button().tap()
        await waitFor { h.auth.calls.count == 2 }
        await waitFor { h.model.state == .linkSent(secondsLeft: 30, resending: false, error: nil) }
    }

    @Test("CORE-01: AC3 invalid email shows an inline error and sends nothing")
    func invalidEmail() async throws {
        for bad in ["", "ada", "ada@", "@test.local", "ada@test", "a b@test.local"] {
            let h = Harness()
            h.model.email = bad
            await h.model.submit()
            #expect(h.model.state == .error(.invalidEmail), "\(bad)")
            #expect(h.auth.calls.isEmpty, "\(bad)")
            _ = try h.view().find(text: "Enter a valid email address.")
        }
    }

    @Test("CORE-01: error codes from the service map to inline messages")
    func serviceErrors() async throws {
        let table: [(AuthError, String)] = [
            (.rateLimited, "Too many attempts. Wait a minute and try again."),
            (.network, "Couldn't reach the server. Check your connection and try again."),
            (.unknown, "Something went wrong. Try again."),
        ]
        for (error, message) in table {
            let h = Harness()
            h.auth.setSendMagicLinkError(error)
            h.model.email = "ada@test.local"
            await h.model.submit()
            #expect(h.model.state == .error(error))
            _ = try h.view().find(text: message)
        }
    }

    @Test("CORE-01: AC2 submit button calls sendMagicLink with the callback URL")
    func submitButton() async throws {
        let h = Harness()
        h.model.email = " ada@test.local "
        try h.view().find(viewWithAccessibilityIdentifier: "signin.send").button().tap()
        await waitFor { !h.auth.calls.isEmpty }
        #expect(h.auth.calls == [.sendMagicLink(email: "ada@test.local", redirectTo: callback)])
    }

    @Test("CORE-01: AC5 Google calls signInWithGoogle with the custom-scheme redirect")
    func google() async throws {
        let h = Harness()
        try h.view().find(viewWithAccessibilityIdentifier: "signin.google").button().tap()
        await waitFor { !h.auth.calls.isEmpty }
        #expect(h.auth.calls == [.signInWithGoogle(redirectTo: callback)])
    }

    @Test("CORE-01: AC5 provider spinners sit on the control that was used")
    func providerSpinners() async throws {
        let google = Harness()
        google.auth.holdSignIn()
        let g = Task { await google.model.signInWithGoogle() }
        await waitFor { google.model.action == .google }
        var view = try google.view()
        _ = try view.find(viewWithAccessibilityIdentifier: "signin.google.spinner")
        #expect(throws: (any Error).self) { try view.find(viewWithAccessibilityIdentifier: "signin.spinner") }
        google.auth.releaseSignIn()
        await g.value
        #expect(google.model.action == nil)

        let apple = Harness()
        apple.auth.holdSignIn()
        let a = Task { await apple.model.signInWithApple(idToken: "t", nonce: "n") }
        await waitFor { apple.model.action == .apple }
        view = try apple.view()
        _ = try view.find(viewWithAccessibilityIdentifier: "signin.apple.spinner")
        #expect(throws: (any Error).self) { try view.find(viewWithAccessibilityIdentifier: "signin.spinner") }
        apple.auth.releaseSignIn()
        await a.value
    }

    @Test("CORE-01: AC5 Apple passes the ID token and raw nonce to signInWithApple")
    func apple() async {
        let h = Harness()
        await h.model.signInWithApple(idToken: "token", nonce: "raw")
        #expect(h.auth.calls == [.signInWithApple(idToken: "token", nonce: "raw")])
    }

    @Test("CORE-01: dismissing a provider sheet is not an error")
    func cancelledProvider() async {
        let h = Harness()
        h.auth.setGoogleError(.cancelled)
        await h.model.signInWithGoogle()
        #expect(h.model.state == .idle)
        h.model.appleFailed(.cancelled)
        #expect(h.model.state == .idle)
        h.model.appleFailed(.unknown)
        #expect(h.model.state == .error(.unknown))
    }

    @Test("CORE-01: AC7 offline disables every button and shows the banner")
    func offline() throws {
        let h = Harness()
        let view = try h.view(online: false)
        _ = try view.find(viewWithAccessibilityIdentifier: "offline-banner")
        _ = try view.find(text: "You're offline. Connect to sign in.")
        for id in ["signin.send", "signin.apple", "signin.google"] {
            #expect(try h.isDisabled(id, online: false), "\(id)")
        }
    }
}

@MainActor
@Suite("CORE-01 session")
struct SessionBootstrapTests {
    @Test("CORE-01: AC6 a stored session goes straight to signedIn without any sign-in call")
    func storedSession() async {
        let user = AuthSession(userId: UUID(), email: "ada@test.local")
        let auth = FakeAuthService(session: user)
        let model = SessionModel(
            auth: auth, lastHousehold: LastHouseholdStore(suiteName: "SessionBootstrapTests-\(UUID())"),
            profileCache: ProfileCache(suiteName: "SessionBootstrapTests-profile-\(UUID())"),
            isOnline: { true }
        )
        let started = Task { await model.start() }
        await waitFor { model.status != .loading }
        await model.waitUntilLoaded()
        #expect(model.status == .signedIn)
        #expect(auth.calls.isEmpty)
        started.cancel()
    }

    @Test("CORE-01: AC1 no stored session is signedOut")
    func noSession() async {
        let model = SessionModel(
            auth: FakeAuthService(), lastHousehold: LastHouseholdStore(suiteName: "SessionBootstrapTests-\(UUID())"),
            profileCache: ProfileCache(suiteName: "SessionBootstrapTests-profile-\(UUID())"),
            isOnline: { true }
        )
        let started = Task { await model.start() }
        await waitFor { model.status != .loading }
        #expect(model.status == .signedOut)
        started.cancel()
    }
}
