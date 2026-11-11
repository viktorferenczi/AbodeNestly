import SwiftUI
import UI

/// DESIGN B2. States: idle, creating, joining, error-invalid-code, offline.
public struct WelcomeView: View {
    @Bindable private var model: WelcomeModel
    private let displayName: String?
    private let isOnline: Bool
    private let onSignOut: () -> Void

    public init(model: WelcomeModel, displayName: String?, isOnline: Bool, onSignOut: @escaping () -> Void) {
        self.model = model
        self.displayName = displayName
        self.isOnline = isOnline
        self.onSignOut = onSignOut
    }

    private var disabled: Bool { !isOnline || model.isBusy }

    // Copied from web `CREATE_MESSAGES` / `JOIN_MESSAGES`.
    static func createMessage(for failure: ApiFailure) -> String {
        switch failure {
        case .validation: "Enter a name for your household."
        case .network: "Couldn't reach the server. Check your connection and try again."
        case .invalidCode, .unknown: "Something went wrong. Try again."
        }
    }

    static func joinMessage(for failure: ApiFailure) -> String {
        switch failure {
        case .validation: "Enter your invite code."
        case .invalidCode: "That code isn't valid or has expired. Check it and try again."
        case .network: "Couldn't reach the server. Check your connection and try again."
        case .unknown: "Something went wrong. Try again."
        }
    }

    private var createError: String? {
        if case .createError(let failure) = model.state { Self.createMessage(for: failure) } else { nil }
    }

    private var joinError: String? {
        switch model.state {
        case .errorInvalidCode: Self.joinMessage(for: .invalidCode)
        case .joinError(let failure): Self.joinMessage(for: failure)
        default: nil
        }
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(displayName.flatMap { $0.isEmpty ? nil : "Welcome, \($0)" } ?? "Welcome")
                    .font(.title.weight(.semibold))
                    .foregroundStyle(Tokens.text)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("welcome.title")
                if !isOnline {
                    OfflineBanner(message: "You're offline. Connect to create or join a household.")
                }
                card(
                    title: "Create a household", label: "Household name", field: "welcome.name",
                    text: $model.name, error: createError, errorId: "welcome.createError"
                ) {
                    action(
                        "Create household", busy: model.state == .creating,
                        button: "welcome.create", spinner: "welcome.creating"
                    ) { Task { await model.create() } }
                }
                card(
                    title: "Join with a code", label: "Invite code", field: "welcome.code",
                    text: $model.code, error: joinError, errorId: "welcome.joinError", isCode: true
                ) {
                    action(
                        "Join household", busy: model.state == .joining,
                        button: "welcome.join", spinner: "welcome.joining"
                    ) { Task { await model.join() } }
                }
                Button("Sign out", action: onSignOut)
                    .font(.body)
                    .foregroundStyle(Tokens.accent)
                    .frame(minWidth: Tokens.minTouchTarget, minHeight: Tokens.minTouchTarget)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("welcome.signout")
            }
            .padding(Tokens.screenPadding)
            .frame(maxWidth: 480, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Tokens.bg)
        .accessibilityIdentifier("welcome.screen")
    }

    private func card(
        title: String, label: String, field: String, text: Binding<String>, error: String?, errorId: String,
        isCode: Bool = false, @ViewBuilder action: () -> some View
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
            Text(label).font(.subheadline).foregroundStyle(Tokens.text2)
                .accessibilityHidden(true)
            TextField("", text: text)
                .textInputAutocapitalization(isCode ? .never : .words)
                .autocorrectionDisabled(isCode)
                .submitLabel(.go)
                .padding(12)
                .frame(minHeight: Tokens.minTouchTarget)
                .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                .overlay(
                    RoundedRectangle(cornerRadius: Tokens.cardRadius)
                        .stroke(error == nil ? Tokens.line : Tokens.danger)
                )
                .accessibilityLabel(label)
                .accessibilityIdentifier(field)
            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier(errorId)
            }
            action()
        }
        .padding(Tokens.screenPadding)
        .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
        .overlay(RoundedRectangle(cornerRadius: Tokens.cardRadius).stroke(Tokens.line))
        .accessibilityElement(children: .contain)
    }

    private func action(
        _ title: String, busy: Bool, button: String, spinner: String, perform: @escaping () -> Void
    ) -> some View {
        Button(action: perform) {
            HStack(spacing: 8) {
                if busy { ProgressView().tint(.white).accessibilityIdentifier(spinner) }
                Text(title)
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            .background(Tokens.accent, in: RoundedRectangle(cornerRadius: 8))
            .opacity(disabled ? 0.4 : 1)
        }
        .disabled(disabled)
        .accessibilityIdentifier(button)
        .accessibilityLabel(busy ? "\(title), in progress" : title)
    }
}
