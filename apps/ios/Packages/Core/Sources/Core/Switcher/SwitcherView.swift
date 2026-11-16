import Contract
import SwiftUI
import UI

/// DESIGN B3 household switcher sheet. States: one-household, many, switching.
public struct SwitcherView: View {
    @Bindable private var model: SwitcherModel
    private let onDismiss: () -> Void
    private let onCreateOrJoin: () -> Void

    public init(model: SwitcherModel, onDismiss: @escaping () -> Void, onCreateOrJoin: @escaping () -> Void) {
        self.model = model
        self.onDismiss = onDismiss
        self.onCreateOrJoin = onCreateOrJoin
    }

    private var isSwitching: Bool {
        if case .switching = model.state { true } else { false }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Households")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
                .padding(Tokens.screenPadding)
            if case .switching(let name) = model.state {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Switching to \(name)…").foregroundStyle(Tokens.text2)
                }
                .padding(.horizontal, Tokens.screenPadding)
                .padding(.bottom, 8)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("switcher.switching")
            }
            if let error = model.errorText {
                Text(error)
                    .font(.subheadline)
                    .foregroundStyle(Tokens.danger)
                    .padding(.horizontal, Tokens.screenPadding)
                    .padding(.bottom, 8)
                    .accessibilityIdentifier("switcher.error")
            }
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(model.rows) { row in
                        rowView(row)
                        Tokens.line.frame(height: 1)
                    }
                    createRow
                }
            }
            // While a switch runs nothing in the list takes touches.
            .disabled(isSwitching)
        }
        .background(Tokens.bg)
        .presentationDetents([.medium, .large])
        .task { await model.load() }
    }

    private func rowView(_ row: SwitcherRow) -> some View {
        // Tap and long-press on one element (no `Button`): a long press must not also fire the tap (double switch).
        HStack(spacing: 12) {
            Image(systemName: row.isCurrent ? "checkmark" : "circle")
                .opacity(row.isCurrent ? 1 : 0)
                .foregroundStyle(Tokens.accent)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.household.name).foregroundStyle(Tokens.text)
                Text(Self.detail(row)).font(.subheadline).foregroundStyle(Tokens.text2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tokens.screenPadding)
        .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { Task { if await model.select(row.id) { onDismiss() } } }
        .onLongPressGesture(minimumDuration: 0.5) { Task { if await model.openSettings(row.id) { onDismiss() } } }
        .accessibilityElement(children: .combine)
        // Explicit, so VoiceOver does not read the check mark's symbol name.
        .accessibilityLabel("\(row.household.name), \(Self.detail(row))")
        .accessibilityAddTraits(row.isCurrent ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction(named: "Household settings") { Task { if await model.openSettings(row.id) { onDismiss() } } }
        .accessibilityIdentifier(row.elementId)
    }

    static func detail(_ row: SwitcherRow) -> String {
        let role = row.role == .owner ? "Owner" : "Member"
        return row.pending > 0 ? "\(role) · \(row.pending) to sync" : role
    }

    private var createRow: some View {
        Button(action: onCreateOrJoin) {
            HStack(spacing: 12) {
                Image(systemName: "plus").foregroundStyle(Tokens.accent).frame(width: 24)
                Text("Create or join another").foregroundStyle(Tokens.accent)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Tokens.screenPadding)
            .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("switcher.create")
    }
}
