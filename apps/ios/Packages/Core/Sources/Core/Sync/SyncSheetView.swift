import SwiftUI
import Sync
import UI

/// DESIGN B12: status line, "Pending changes: N", "Failed changes: M" with Retry / Discard, "Sync now".
/// Works offline and with every module off (it reads only the outbox).
public struct SyncSheetView: View {
    @Bindable private var model: SyncStatusModel
    private let onClose: () -> Void

    public init(model: SyncStatusModel, onClose: @escaping () -> Void) {
        self.model = model
        self.onClose = onClose
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Sync").font(.title3.weight(.semibold)).foregroundStyle(Tokens.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Done", action: onClose)
                    .accessibilityIdentifier("sync.done")
            }
            Text(model.statusLine)
                .foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("sync.status")
            Text("Pending changes: \(model.counts.pending)")
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("sync.pending")
            Text("Failed changes: \(model.counts.failed)")
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("sync.failed")
            if !model.failed.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(model.failed) { change in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(SyncStatusModel.description(of: change))
                                .font(.subheadline)
                                .foregroundStyle(Tokens.text)
                                .accessibilityIdentifier("sync.failed.row.\(change.seq)")
                            HStack {
                                Button("Retry") { Task { await model.retry(change) } }
                                    .accessibilityIdentifier("sync.retry.\(change.seq)")
                                Button("Discard", role: .destructive) { Task { await model.discard(change) } }
                                    .accessibilityIdentifier("sync.discard.\(change.seq)")
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Failed changes")
            }
            Spacer(minLength: 0)
            Button { Task { await model.syncNow() } } label: {
                Text("Sync now").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.borderedProminent)
            .tint(Tokens.accent)
            .accessibilityIdentifier("sync.now")
        }
        .padding(Tokens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Tokens.bg)
    }
}
