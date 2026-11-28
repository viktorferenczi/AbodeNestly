import SwiftUI
import UI

/// DESIGN C8: the switch "Share with a link", the link with Copy (and the system Share button, an iOS addition), the line
/// about unchecked items, and Stop sharing. Online only: offline the switch and Stop sharing are disabled, while a known
/// link stays visible and copyable. Web twin: `ShareSheet.tsx`.
public struct ShareSheetView: View {
    private let model: ShareSheetModel
    private let isOnline: Bool
    private let onClose: () -> Void

    public init(model: ShareSheetModel, isOnline: Bool, onClose: @escaping () -> Void) {
        self.model = model
        self.isOnline = isOnline
        self.onClose = onClose
    }

    static let offlineMessage = "Sharing needs a connection"
    static let copiedMessage = "Link copied"
    static let uncheckedLine = "Anyone with the link can view unchecked items"
    static let stopHint = "The current link will stop working"

    private var disabled: Bool { !isOnline || model.state.isBusy }

    private var switchBinding: Binding<Bool> {
        Binding(
            get: { model.state.isOn },
            set: { on in Task { await model.setOn(on) } }
        )
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 8) {
                        Toggle("Share with a link", isOn: switchBinding)
                            .disabled(disabled)
                            .accessibilityIdentifier("share.switch")
                        if model.state.isBusy { ProgressView().accessibilityIdentifier("share.busy") }
                    }
                    if !isOnline {
                        hint(Self.offlineMessage, id: "share.offline")
                    }
                    if let error = model.state.error {
                        Text(error.message)
                            .font(.footnote).foregroundStyle(Tokens.danger)
                            .accessibilityIdentifier("share.error")
                    }
                    if let link = model.link {
                        linkBlock(link)
                    }
                }
                .padding(Tokens.screenPadding)
                .frame(maxWidth: 480, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Tokens.bg)
            .navigationTitle("Share list")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { onClose() }.accessibilityIdentifier("share.close")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onChange(of: model.state.error) { _, error in
            if let error { AccessibilityNotification.Announcement(error.message).post() }
        }
    }

    @ViewBuilder
    private func linkBlock(_ link: URL) -> some View {
        HStack(spacing: 12) {
            Text(link.absoluteString)
                .font(.subheadline.monospaced())
                .foregroundStyle(Tokens.text)
                .textSelection(.enabled)
                .lineLimit(3)
                .accessibilityLabel("Share link")
                .accessibilityValue(link.absoluteString)
                .accessibilityIdentifier("share.link")
            Spacer(minLength: 0)
        }
        HStack(spacing: 12) {
            Button("Copy link") {
                model.copyLink()
                // Posted per tap so a repeat copy is announced too.
                AccessibilityNotification.Announcement(Self.copiedMessage).post()
            }
            .buttonStyle(.bordered)
            .tint(Tokens.accent)
            .frame(minHeight: Tokens.minTouchTarget)
            .accessibilityIdentifier("share.copy")
            ShareLink(item: link) { Text("Share…") }
                .buttonStyle(.bordered)
                .tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityIdentifier("share.system")
        }
        if model.copied { hint(Self.copiedMessage, id: "share.copied") }
        hint(Self.uncheckedLine, id: "share.unchecked")
        Button(role: .destructive) {
            Task { await model.turnOff() }
        } label: {
            Text("Stop sharing").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
        }
        .buttonStyle(.bordered)
        .disabled(disabled)
        .accessibilityIdentifier("share.stop")
        hint(Self.stopHint, id: "share.stopHint")
    }

    private func hint(_ text: String, id: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(Tokens.text2).accessibilityIdentifier(id)
    }
}
