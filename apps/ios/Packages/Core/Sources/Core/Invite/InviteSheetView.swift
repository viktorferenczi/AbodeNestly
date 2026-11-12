import Contract
import SwiftUI
import UI

/// DESIGN B6 invite sheet. Role-independent: owners and members see the same thing (SPEC §4 CORE-03).
public struct InviteSheetView: View {
    private let model: InviteSheetModel
    private let isOnline: Bool
    private let webBaseURL: URL
    @Environment(\.dismiss) private var dismiss

    public init(model: InviteSheetModel, isOnline: Bool, webBaseURL: URL) {
        self.model = model
        self.isOnline = isOnline
        self.webBaseURL = webBaseURL
    }

    static let loadErrorMessage = "Couldn't load the invite code. Try again."
    static let newCodeErrorMessage = "Couldn't create a new code. The current code still works."
    static let oldCodeMessage = "The new code is ready, but the old code may still work."

    /// The link `ShareLink` hands to the system share sheet.
    var shareURL: URL? {
        model.shownInvite.map { InviteSheetModel.link(base: webBaseURL, code: $0.code) }
    }

    private var rotating: Bool { model.isRotating }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if !isOnline {
                        OfflineBanner(message: "You're offline. Connect to change household settings.")
                    }
                    content
                    if model.shownInvite != nil, model.hasFailed {
                        errorText(Self.newCodeErrorMessage, id: "invite.error")
                    }
                    if model.oldCodeMayWork {
                        errorText(Self.oldCodeMessage, id: "invite.oldCode")
                    }
                    if let notice = model.notice {
                        Text(notice).font(.footnote).foregroundStyle(Tokens.text2)
                            .accessibilityIdentifier("invite.notice")
                    }
                    share
                }
                .padding(Tokens.screenPadding)
                .frame(maxWidth: 480, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Tokens.bg)
            .navigationTitle("Invite to household")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }.accessibilityIdentifier("invite.close")
                }
            }
        }
        .presentationDetents([.medium, .large])
        // Loads on appear and again on reconnect.
        .task(id: isOnline) { if isOnline { await model.loadIfNeeded() } }
        .onChange(of: announcement) { _, message in
            if let message { AccessibilityNotification.Announcement(message).post() }
        }
        .accessibilityIdentifier("invite.sheet")
    }

    private var announcement: String? {
        if model.oldCodeMayWork { return Self.oldCodeMessage }
        if model.hasFailed { return model.shownInvite == nil ? Self.loadErrorMessage : Self.newCodeErrorMessage }
        return nil
    }

    @ViewBuilder
    private var content: some View {
        if let invite = model.shownInvite {
            HStack(spacing: 12) {
                Text(invite.code)
                    .font(.system(.largeTitle, design: .monospaced).weight(.semibold))
                    .foregroundStyle(Tokens.text)
                    .textSelection(.enabled)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .accessibilityLabel("Invite code \(invite.code)")
                    .accessibilityIdentifier("invite.code")
                Spacer(minLength: 0)
                Button("Copy") { model.copyCode() }
                    .buttonStyle(.bordered)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .disabled(rotating)
                    .accessibilityIdentifier("invite.copy")
            }
            Text(InviteSheetModel.metaText(invite))
                .font(.subheadline).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("invite.meta")
            Button {
                Task { await model.newCode() }
            } label: {
                HStack(spacing: 8) {
                    if rotating { ProgressView().accessibilityIdentifier("invite.rotating") }
                    Text("New code")
                }
                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.bordered)
            .tint(Tokens.accent)
            .disabled(!isOnline || rotating)
            .accessibilityLabel(rotating ? "New code, in progress" : "New code")
            .accessibilityIdentifier("invite.newCode")
        } else if !isOnline {
            Text("Connect to see the invite code.").font(.subheadline).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("invite.connect")
        } else if model.hasFailed {
            errorText(Self.loadErrorMessage, id: "invite.loadError")
            Button("Try again") { Task { await model.loadIfNeeded() } }
                .buttonStyle(.bordered).tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityIdentifier("invite.retry")
        } else {
            Text("Loading…").font(.subheadline).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("invite.loading")
        }
    }

    @ViewBuilder
    private var share: some View {
        let enabled = isOnline && !rotating && shareURL != nil
        if let url = shareURL {
            ShareLink(item: url, subject: Text("Join my household")) {
                shareLabel
            }
            .disabled(!enabled)
            .accessibilityIdentifier("invite.share")
        } else {
            Button {} label: { shareLabel }
                .disabled(true)
                .accessibilityIdentifier("invite.share")
        }
    }

    private var shareLabel: some View {
        Text("Share link")
            .font(.body.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            .background(Tokens.accent, in: RoundedRectangle(cornerRadius: 8))
            .opacity(isOnline && !rotating ? 1 : 0.4)
    }

    private func errorText(_ message: String, id: String) -> some View {
        Text(message).font(.footnote).foregroundStyle(Tokens.danger).accessibilityIdentifier(id)
    }
}
