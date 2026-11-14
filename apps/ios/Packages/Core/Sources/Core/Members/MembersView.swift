import Contract
import SwiftUI
import UI

/// DESIGN B6: member rows (avatar, name, role, "you"), the owner overflow menu, Leave, the Invite sheet.
/// Everything is read-only offline, with the list taken from the last snapshot.
public struct MembersView: View {
    private let model: MembersModel
    private let api: any ApiService
    private let isOnline: Bool
    private let webBaseURL: URL
    /// Created on tap and kept for the sheet's life, so a parent re-render never resets its state.
    @State private var invite: InviteSheetModel?

    public init(model: MembersModel, api: any ApiService, isOnline: Bool, webBaseURL: URL) {
        self.model = model
        self.api = api
        self.isOnline = isOnline
        self.webBaseURL = webBaseURL
    }

    private var actionsEnabled: Bool { isOnline && !model.isBusy }

    public var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    heading
                    if !isOnline {
                        OfflineBanner(message: "You're offline. Connect to change household settings.")
                    }
                    content
                }
                .padding(Tokens.screenPadding)
                .frame(maxWidth: .infinity, alignment: .topLeading)
            }
            if let toast = model.toast {
                UndoToast(message: toast) { model.undo() }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(Tokens.bg)
        .navigationTitle("Members")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $invite) { model in
            InviteSheetView(model: model, isOnline: isOnline, webBaseURL: webBaseURL)
        }
        .alert(
            "Members",
            isPresented: Binding(get: { model.alert != nil }, set: { if !$0 { model.alert = nil } }),
            actions: { Button("OK", role: .cancel) {} },
            message: { Text(model.alert ?? "") }
        )
        // Loads on appear and again when connectivity changes.
        .task(id: isOnline) { await model.load() }
        // A pending removal is sent now rather than dropped: Undo is the only cancel.
        .onDisappear { model.flushPending() }
        .onChange(of: model.toast) { _, message in
            if let message { AccessibilityNotification.Announcement(message).post() }
        }
        .animation(.default, value: model.toast)
    }

    private var heading: some View {
        HStack {
            Text("Members")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button("Invite") { invite = InviteSheetModel(householdId: model.householdId, api: api) }
                .buttonStyle(.borderedProminent)
                .tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .disabled(!isOnline)
                .accessibilityIdentifier("members.invite")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.loadState {
        case .loading:
            Text("Loading…").font(.subheadline).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("members.loading")
        case .offlineNoCache:
            Text("Connect to see the members.").font(.subheadline).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("members.connect")
        case .error:
            Text("Couldn't load members. Try again.").font(.footnote).foregroundStyle(Tokens.danger)
                .accessibilityIdentifier("members.error")
            Button("Try again") { Task { await model.load() } }
                .buttonStyle(.bordered).tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityIdentifier("members.retry")
        case .loaded:
            VStack(spacing: 0) {
                ForEach(model.visibleRows) { row in
                    memberRow(row)
                    Tokens.line.frame(height: 1)
                }
            }
            Button(role: .destructive) {
                model.leave()
            } label: {
                Text("Leave household").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.bordered)
            .tint(Tokens.danger)
            .disabled(!actionsEnabled)
            .accessibilityIdentifier("members.leave")
        }
    }

    private func memberRow(_ row: MemberRow) -> some View {
        let name = MembersModel.displayName(row)
        let id = row.id.uuidString.lowercased()
        let role = row.member.role == .owner ? "Owner" : "Member"
        return HStack(spacing: 12) {
            AvatarView(initials: MembersModel.initials(name))
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.body).foregroundStyle(Tokens.text)
                    .accessibilityIdentifier("members.row.\(id).name")
                Text(role).font(.footnote).foregroundStyle(Tokens.text2)
                    .accessibilityIdentifier("members.row.\(id).role")
            }
            Spacer(minLength: 8)
            if row.id == model.meId {
                Text("You").font(.footnote.weight(.semibold)).foregroundStyle(Tokens.accent)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Tokens.accentBg, in: Capsule())
                    .accessibilityIdentifier("members.row.\(id).you")
            }
            if MembersModel.canRemove(role: model.role, target: row, meId: model.meId) {
                overflowMenu(row, name: name, id: id)
            }
        }
        .padding(.vertical, 8)
        .frame(minHeight: Tokens.minTouchTarget)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("members.row.\(id)")
    }

    /// Items are only built when actions are enabled, so a stale open menu cannot act offline or mid-removal.
    private func overflowMenu(_ row: MemberRow, name: String, id: String) -> some View {
        Menu {
            if actionsEnabled {
                if row.member.role != .owner {
                    Button("Make owner") { Task { await model.makeOwner(row) } }
                        .accessibilityIdentifier("members.row.\(id).makeOwner")
                }
                Button("Remove", role: .destructive) { model.remove(row) }
                    .accessibilityIdentifier("members.row.\(id).remove")
            }
        } label: {
            Image(systemName: "ellipsis")
                .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                .contentShape(Rectangle())
        }
        .tint(Tokens.text2)
        .disabled(!actionsEnabled)
        .accessibilityLabel("More actions for \(name)")
        .accessibilityIdentifier("members.row.\(id).menu")
    }
}
