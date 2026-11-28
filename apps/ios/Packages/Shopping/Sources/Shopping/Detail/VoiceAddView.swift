import SwiftUI
import UI

/// DESIGN C6, a medium-detent sheet opened from the mic button of the list's QuickAddBar. States: listening (pulsing mic,
/// live transcript), parsed (chips, Add, Try again), no item, not heard, permission-denied and unsupported (both point
/// back at the keyboard). Web twin: `VoiceAdd.tsx`.
public struct VoiceAddView: View {
    @Bindable private var model: VoiceAddModel
    private let onClose: () -> Void
    private let onTypeInstead: () -> Void
    private let openSettings: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    /// `onTypeInstead` closes the sheet and puts the cursor in the Quick add field. `openSettings` opens the app's page in Settings.
    public init(
        model: VoiceAddModel, onClose: @escaping () -> Void, onTypeInstead: @escaping () -> Void, openSettings: @escaping () -> Void
    ) {
        self.model = model
        self.onClose = onClose
        self.onTypeInstead = onTypeInstead
        self.openSettings = openSettings
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Voice add")
                .font(.headline)
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("voice.title")
            if model.addFailed {
                Text("Could not save the change. Try again.")
                    .font(.subheadline)
                    .foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("voice.error")
            }
            content
            Spacer(minLength: 0)
        }
        .padding(Tokens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Tokens.bg)
        .task { await model.start() }
        .onDisappear { model.stop() }
        // Polite updates: the live transcript and a failed Add are read out without taking focus (web: aria-live, role="alert").
        .onChange(of: model.state) { _, state in
            if case .listening(let partial) = state, !partial.isEmpty { AccessibilityNotification.Announcement(partial).post() }
        }
        .onChange(of: model.addFailed) { _, failed in
            if failed { AccessibilityNotification.Announcement("Could not save the change. Try again.").post() }
        }
        .accessibilityIdentifier("voice.screen")
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .listening(let partial):
            listening(partial)
        case .parsed(let transcript, let item):
            parsed(transcript, item)
        case .noItem(let transcript):
            Text(transcript)
                .foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("voice.transcript")
            hintAndRetry("Didn't catch an item. Try again.")
        case .notHeard:
            hintAndRetry("Didn't catch that.")
        case .permissionDenied:
            blocked(
                title: "Voice access is off",
                message: "Microphone or speech access is off. Allow it in Settings, or type the item instead.",
                settings: true
            )
        case .unsupported:
            blocked(title: "Voice isn't available", message: "Voice input isn't available here. Type the item instead.", settings: false)
        }
    }

    // MARK: states

    /// Still under Reduce Motion: no animation is attached at all.
    private var pulseAnimation: Animation? {
        reduceMotion ? nil : Animation.easeInOut(duration: 0.8).repeatForever(autoreverses: true)
    }

    private func listening(_ partial: String) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "mic.fill")
                .font(.system(size: 44))
                .foregroundStyle(Tokens.accent)
                .scaleEffect(pulse && !reduceMotion ? 1.18 : 1)
                .animation(pulseAnimation, value: pulse)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
                .accessibilityIdentifier("voice.indicator")
                .onAppear { pulse = true }
            Text("Listening…")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("voice.listening")
            Text(partial)
                .foregroundStyle(Tokens.text2)
                .frame(maxWidth: .infinity, minHeight: 24)
                .accessibilityIdentifier("voice.transcript")
            Button("Cancel") {
                model.stop()
                onClose()
            }
            .buttonStyle(.bordered)
            .tint(Tokens.accent)
            .frame(minHeight: Tokens.minTouchTarget)
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("voice.cancel")
        }
    }

    private func parsed(_ transcript: String, _ item: SpokenItem) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(transcript)
                .foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("voice.transcript")
            HStack(spacing: 8) {
                if let quantity = item.quantity {
                    chip(ItemSheetLogic.formatQty(quantity, nil), label: "Quantity", id: "voice.chip.quantity")
                }
                if let unit = item.unit, !unit.isEmpty {
                    chip(unit, label: "Unit", id: "voice.chip.unit")
                }
                chip(item.name, label: "Name", id: "voice.chip.name")
            }
            HStack(spacing: 12) {
                Button("Try again") { Task { await model.tryAgain() } }
                    .buttonStyle(.bordered)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .disabled(model.adding)
                    .accessibilityIdentifier("voice.tryagain")
                Button("Add") {
                    Task {
                        if await model.addParsed() {
                            model.stop()
                            onClose()
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .disabled(model.adding)
                .accessibilityIdentifier("voice.add")
            }
        }
    }

    /// Read-only; each chip is its own VoiceOver element ("Quantity 2", "Unit l", "Name Milk").
    private func chip(_ value: String, label: String, id: String) -> some View {
        Text(value)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(Tokens.text)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Tokens.accentBg, in: Capsule())
            .accessibilityLabel("\(label) \(value)")
            .accessibilityIdentifier(id)
    }

    private func hintAndRetry(_ hint: String) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(hint)
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("voice.hint")
            Button("Try again") { Task { await model.tryAgain() } }
                .buttonStyle(.borderedProminent)
                .tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .disabled(model.adding)
                .accessibilityIdentifier("voice.tryagain")
        }
    }

    private func blocked(title: String, message: String, settings: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("voice.blocked.title")
            Text(message)
                .foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("voice.blocked.message")
            HStack(spacing: 12) {
                if settings {
                    Button("Open Settings", action: openSettings)
                        .buttonStyle(.bordered)
                        .tint(Tokens.accent)
                        .frame(minHeight: Tokens.minTouchTarget)
                        .accessibilityIdentifier("voice.settings")
                }
                Button("Type instead", action: onTypeInstead)
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityIdentifier("voice.typeinstead")
            }
        }
    }
}
