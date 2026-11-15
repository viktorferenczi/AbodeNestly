import Contract
import SwiftUI
import UI

/// DESIGN B5: name, module switches, currency and Leave. Read-only for members and offline.
public struct HouseholdSettingsView: View {
    private let model: HouseholdSettingsModel
    private let isOnline: Bool
    @FocusState private var nameFocused: Bool

    public init(model: HouseholdSettingsModel, isOnline: Bool) {
        self.model = model
        self.isOnline = isOnline
    }

    private var mode: SettingsMode { model.mode(isOnline: isOnline) }
    private var locked: Bool { mode != .owner || model.isSaving }

    public var body: some View {
        content
            .background(Tokens.bg)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: Binding(get: { model.isConfirmingLeave }, set: { if !$0 { model.cancelLeave() } })) {
                LeaveHouseholdSheet(model: model, isOnline: isOnline)
            }
            // Loads on appear and again when connectivity changes.
            .task(id: isOnline) { await model.load() }
            // A visual-only status or error is silent in VoiceOver (web uses aria-live).
            .onChange(of: model.isSaving) { _, saving in
                if saving { AccessibilityNotification.Announcement("Saving").post() }
            }
            .onChange(of: model.saveErrorMessage) { _, message in
                if let message { AccessibilityNotification.Announcement(message).post() }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch (model.loadState, model.shown) {
        case (.loaded, let household?):
            form(household)
        case (.unavailable, _):
            Text("Couldn't load household settings.")
                .font(.subheadline).foregroundStyle(Tokens.text2)
                .padding(Tokens.screenPadding)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .accessibilityIdentifier("settings.unavailable")
        default:
            Text("Loading…")
                .font(.subheadline).foregroundStyle(Tokens.text2)
                .padding(Tokens.screenPadding)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .accessibilityIdentifier("settings.loading")
        }
    }

    private func form(_ household: Household) -> some View {
        Form {
            if mode == .offline {
                Section {
                    OfflineBanner(message: "You're offline. Connect to change household settings.")
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            Section("Household name") {
                TextField("Household name", text: Binding(get: { model.name }, set: { model.nameDraft = $0 }))
                    .focused($nameFocused)
                    .submitLabel(.done)
                    .onSubmit { Task { await model.commitName() } }
                    .onChange(of: nameFocused) { _, focused in
                        if !focused { Task { await model.commitName() } }
                    }
                    .disabled(locked)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityLabel("Household name")
                    .accessibilityIdentifier("settings.name")
            }
            Section("Modules") {
                ForEach(ModuleKey.allCases) { module in
                    moduleRow(module, household: household)
                }
            }
            Section {
                Picker(
                    "Currency",
                    selection: Binding(
                        get: { household.currency }, set: { value in Task { await model.setCurrency(value) } }
                    )
                ) {
                    ForEach(HouseholdSettingsModel.currencyOptions(current: household.currency), id: \.self) { Text($0).tag($0) }
                }
                .disabled(locked)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityLabel("Currency")
                .accessibilityIdentifier("settings.currency")
            }
            Section {
                if model.isSaving {
                    Text("Saving…").font(.subheadline).foregroundStyle(Tokens.text2)
                        .accessibilityIdentifier("settings.saving")
                }
                if let message = model.saveErrorMessage {
                    Text(message).font(.footnote).foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("settings.error")
                }
            }
            if model.role != nil {
                Section {
                    Button(role: .destructive) {
                        model.beginLeave()
                    } label: {
                        Text("Leave household").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
                    }
                    .disabled(mode == .offline || model.isSaving)
                    .accessibilityIdentifier("settings.leave")
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    private func moduleRow(_ module: ModuleKey, household: Household) -> some View {
        Toggle(isOn: Binding(
            get: { HouseholdSettingsModel.isOn(household, module) },
            set: { on in Task { await model.toggle(module, on: on) } }
        )) {
            VStack(alignment: .leading, spacing: 2) {
                Text(module.label).font(.body).foregroundStyle(Tokens.text)
                Text(module.summary).font(.footnote).foregroundStyle(Tokens.text2)
            }
        }
        .tint(Tokens.accent)
        .disabled(locked)
        .frame(minHeight: Tokens.minTouchTarget)
        .accessibilityLabel(module.label)
        .accessibilityHint(module.summary)
        .accessibilityIdentifier("settings.module.\(module.rawValue)")
    }
}

/// The confirm sheet for Leave (DESIGN B5): Cancel / Leave, with the last-owner refusal shown inline.
struct LeaveHouseholdSheet: View {
    let model: HouseholdSettingsModel
    let isOnline: Bool

    var body: some View {
        let leaving = model.state == .leaving
        return VStack(alignment: .leading, spacing: 16) {
            Text("Leave \(model.household?.name ?? "household")?")
                .font(.title3.weight(.semibold)).foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
            Text("You'll lose access to this household's lists until someone invites you back.")
                .font(.subheadline).foregroundStyle(Tokens.text2)
            if let message = model.leaveErrorMessage {
                Text(message).font(.footnote).foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("settings.leave.error")
            }
            HStack(spacing: 12) {
                Button("Cancel") { model.cancelLeave() }
                    .buttonStyle(.bordered).tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .disabled(leaving)
                    .accessibilityIdentifier("settings.leave.cancel")
                Button(role: .destructive) {
                    Task { await model.leave() }
                } label: {
                    Text("Leave").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
                }
                .buttonStyle(.borderedProminent).tint(Tokens.danger)
                .disabled(leaving || !isOnline)
                .accessibilityIdentifier("settings.leave.confirm")
            }
        }
        .padding(Tokens.screenPadding)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Tokens.bg)
        .onChange(of: model.leaveErrorMessage) { _, message in
            if let message { AccessibilityNotification.Announcement(message).post() }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(leaving)
    }
}
