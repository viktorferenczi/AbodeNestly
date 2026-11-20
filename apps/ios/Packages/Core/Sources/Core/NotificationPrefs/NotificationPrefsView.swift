import SwiftUI
import UI

/// DESIGN B9. States: idle, permission-denied (banner; the switches stay). `openURL` is injected so tests can see the link.
public struct NotificationPrefsView: View {
    @Bindable private var model: NotificationPrefsModel
    private let openURL: (URL) -> Void
    @Environment(\.scenePhase) private var scenePhase

    public init(model: NotificationPrefsModel, openURL: @escaping (URL) -> Void) {
        self.model = model
        self.openURL = openURL
    }

    public var body: some View {
        VStack(spacing: 0) {
            if model.loaded {
                content
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Tokens.bg)
        .task { await model.refreshAuthorization() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshAuthorization() } }
        }
    }

    private var content: some View {
        List {
            if model.permissionDenied {
                Text("Notifications are turned off for AbodeNestly in iOS settings. Open the system notification settings to receive these notices.")
                    .foregroundStyle(Tokens.text)
                    .accessibilityIdentifier("notif.denied")
            }
            Section {
                ForEach(model.types) { type in
                    Toggle(type.title, isOn: Binding(
                        get: { model.isOn(type) },
                        set: { on in Task { await model.set(type, enabled: on) } }
                    ))
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityIdentifier("notif.switch.\(type.rawValue)")
                }
            }
            if model.saveFailed {
                Text("Could not save that change.").font(.footnote).foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("notif.problem")
            }
            Section {
                Button("System notification settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
                }
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityIdentifier("notif.settings")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }
}
