import AuthenticationServices
import SwiftUI
import UI

/// DESIGN B1. States: idle, sending, link-sent, error, offline.
public struct SignInView: View {
    @Bindable private var model: SignInModel
    private let isOnline: Bool
    @State private var nonce = Nonce()

    public init(model: SignInModel, isOnline: Bool) {
        self.model = model
        self.isOnline = isOnline
    }

    private var disabled: Bool { !isOnline || model.isBusy }

    static func message(for error: AuthError) -> String {
        switch error {
        case .invalidEmail: "Enter a valid email address."
        case .rateLimited: "Too many attempts. Wait a minute and try again."
        case .network: "Couldn't reach the server. Check your connection and try again."
        case .cancelled, .unknown: "Something went wrong. Try again."
        }
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("AbodeNestly")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Tokens.accent)
                Text("Sign in to AbodeNestly")
                    .font(.title.weight(.semibold))
                    .foregroundStyle(Tokens.text)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("signin.title")
                if !isOnline {
                    OfflineBanner(message: "You're offline. Connect to sign in.")
                }
                if case .linkSent(let secondsLeft, let resending, let error) = model.state {
                    linkSent(secondsLeft: secondsLeft, resending: resending, error: error)
                } else {
                    form
                    divider
                    providers
                }
                Text("By continuing you agree to the Terms of Service and Privacy Policy.")
                    .font(.footnote)
                    .foregroundStyle(Tokens.text2)
            }
            .padding(Tokens.screenPadding)
            .frame(maxWidth: 480, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Tokens.bg)
        .accessibilityIdentifier("signin.screen")
    }

    private var fieldError: AuthError? {
        if case .error(let error) = model.state { error } else { nil }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Email").font(.subheadline).foregroundStyle(Tokens.text2)
            TextField("", text: $model.email)
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.send)
                .onSubmit { send() }
                .padding(12)
                .frame(minHeight: Tokens.minTouchTarget)
                .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                .overlay(
                    RoundedRectangle(cornerRadius: Tokens.cardRadius)
                        .stroke(fieldError == nil ? Tokens.line : Tokens.danger)
                )
                .accessibilityLabel("Email")
                .accessibilityIdentifier("signin.email")
            if let fieldError {
                Text(Self.message(for: fieldError))
                    .font(.footnote)
                    .foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("signin.error")
            }
            Button(action: send) {
                HStack(spacing: 8) {
                    if model.isBusy { ProgressView().accessibilityIdentifier("signin.spinner") }
                    Text("Send magic link")
                }
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.borderedProminent)
            .tint(Tokens.accent)
            .disabled(disabled)
            .accessibilityIdentifier("signin.send")
        }
    }

    private var divider: some View {
        HStack(spacing: 12) {
            Tokens.line.frame(height: 1)
            Text("or").font(.subheadline).foregroundStyle(Tokens.text2)
            Tokens.line.frame(height: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityHidden(true)
    }

    private var providers: some View {
        VStack(spacing: 12) {
            SignInWithAppleButton(
                .signIn,
                onRequest: { request in
                    nonce = Nonce()
                    request.requestedScopes = [.fullName, .email]
                    request.nonce = nonce.hashed
                },
                onCompletion: handleApple
            )
            .signInWithAppleButtonStyle(.black)
            .frame(height: Tokens.minTouchTarget)
            .disabled(disabled)
            .accessibilityIdentifier("signin.apple")
            Button {
                Task { await model.signInWithGoogle() }
            } label: {
                HStack(spacing: 8) {
                    Image("GoogleG", bundle: .module)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 18, height: 18)
                        .accessibilityHidden(true)
                    Text("Sign in with Google")
                        .font(.body.weight(.semibold))
                }
                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.bordered)
            .tint(Tokens.text)
            .disabled(disabled)
            .accessibilityIdentifier("signin.google")
        }
    }

    private func linkSent(secondsLeft: Int, resending: Bool, error: AuthError?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Check your email")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("signin.linkSent")
            Text("We sent a sign-in link to \(model.email.trimmingCharacters(in: .whitespacesAndNewlines)).")
                .foregroundStyle(Tokens.text2)
            Button {
                Task { await model.resend() }
            } label: {
                Text(secondsLeft > 0 ? "Resend (\(secondsLeft) s)" : "Resend")
                    .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.bordered)
            .tint(Tokens.accent)
            .disabled(!isOnline || resending || secondsLeft > 0)
            .accessibilityIdentifier("signin.resend")
            if let error {
                Text(Self.message(for: error))
                    .font(.footnote)
                    .foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("signin.error")
            }
        }
    }

    private func send() {
        guard !disabled else { return }
        Task { await model.submit() }
    }

    private func handleApple(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case .success(let authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                let token = credential.identityToken.flatMap({ String(data: $0, encoding: .utf8) })
            else { return model.appleFailed(.unknown) }
            let raw = nonce.raw
            Task { await model.signInWithApple(idToken: token, nonce: raw) }
        case .failure(let error):
            let cancelled = (error as? ASAuthorizationError)?.code == .canceled
            model.appleFailed(cancelled ? .cancelled : .unknown)
        }
    }
}
