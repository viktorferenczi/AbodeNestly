import SwiftUI

/// What the chip shows (DESIGN A6). Precedence is decided by whoever builds it; the view only draws.
public enum SyncChipState: Equatable, Sendable {
    case hidden
    case syncing
    case offline
    case pending(Int)

    /// The text on the chip, also its VoiceOver label; `nil` when hidden.
    public var text: String? {
        switch self {
        case .hidden: nil
        case .syncing: "Syncing"
        case .offline: "Offline"
        case .pending(let count): "\(count) to sync"
        }
    }
}

/// DESIGN A6 SyncStatusChip: hidden when idle; tapping opens the sync sheet (B12). Web twin: `SyncStatusChip.tsx`.
public struct SyncStatusChip: View {
    private let state: SyncChipState
    private let onTap: () -> Void

    public init(state: SyncChipState, onTap: @escaping () -> Void) {
        self.state = state
        self.onTap = onTap
    }

    public var body: some View {
        if let text = state.text {
            Button(action: onTap) {
                HStack(spacing: 4) {
                    icon
                    Text(text).font(.subheadline)
                }
                .foregroundStyle(Tokens.text2)
                .padding(.horizontal, 10)
                .frame(minHeight: Tokens.minTouchTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(text)
            .accessibilityIdentifier("sync.chip")
        }
    }

    @ViewBuilder
    private var icon: some View {
        switch state {
        case .syncing:
            ProgressView().controlSize(.mini).frame(width: 14, height: 14)
        case .offline:
            Image(systemName: "icloud.slash").imageScale(.small)
        default:
            Image(systemName: "icloud.and.arrow.up").imageScale(.small)
        }
    }
}
