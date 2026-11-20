import Contract
import PhotosUI
import SwiftUI
import UI
import UniformTypeIdentifiers

/// DESIGN B7: avatar (tap to change), display name, read-only email, notification preferences link, sign out.
/// Name edits and photos are saved locally first, so everything but the signed avatar image works offline.
public struct ProfileView<Destination: View>: View {
    private let model: ProfileModel
    private let isOnline: Bool
    /// The pushed notification preferences screen (B9); the app builds it because it owns the household and database.
    private let notifications: () -> Destination
    @State private var pickerItem: PhotosPickerItem?
    @FocusState private var nameFocused: Bool

    public init(model: ProfileModel, isOnline: Bool, @ViewBuilder notifications: @escaping () -> Destination) {
        self.model = model
        self.isOnline = isOnline
        self.notifications = notifications
    }

    private var name: String { model.profile?.displayName ?? "" }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if !isOnline {
                    OfflineBanner(message: "You're offline. Changes will sync when you reconnect.")
                }
                avatarSection
                nameSection
                emailSection
                NavigationLink {
                    notifications()
                } label: {
                    HStack {
                        Text("Notification preferences").foregroundStyle(Tokens.text)
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote).foregroundStyle(Tokens.text2)
                    }
                    .frame(minHeight: Tokens.minTouchTarget)
                    .contentShape(Rectangle())
                }
                .accessibilityIdentifier("profile.notifications")
                if let problem = model.syncProblem {
                    Text(problem).font(.footnote).foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("profile.problem")
                }
                Button(role: .destructive) {
                    Task { await model.signOut() }
                } label: {
                    Text("Sign out").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
                }
                .buttonStyle(.bordered)
                .tint(Tokens.danger)
                .accessibilityIdentifier("profile.signOut")
            }
            .padding(Tokens.screenPadding)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(Tokens.bg)
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        // Drains whatever was queued earlier and resolves the avatar image.
        .task {
            model.resetNameDraft()
            await model.start()
        }
        .task(id: model.profile?.avatarPath) { await model.loadAvatarURL() }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task {
                // `PickedImage` imports any image type, including the HEIC the camera produces.
                if let picked = try? await item.loadTransferable(type: PickedImage.self) {
                    model.pickAvatar(picked.data)
                } else {
                    model.photoLoadFailed()
                }
                pickerItem = nil
            }
        }
        .onChange(of: model.avatar) { _, status in
            // The status lines are the only feedback for the uploading and offline states, so VoiceOver hears them.
            switch status {
            case .idle: break
            case .uploading: AccessibilityNotification.Announcement("Uploading photo…").post()
            case .queued: AccessibilityNotification.Announcement("Photo will upload when you're online").post()
            }
        }
        .onChange(of: name) { _, latest in
            if !nameFocused { model.nameDraft = latest }
        }
        .onChange(of: nameFocused) { _, focused in
            if !focused { model.commitName(model.nameDraft) }
        }
    }

    private var avatarSection: some View {
        VStack(spacing: 8) {
            PhotosPicker(selection: $pickerItem, matching: .images) {
                avatarImage
                    .frame(width: 96, height: 96)
                    .clipShape(Circle())
                    .frame(minWidth: Tokens.minTouchTarget, minHeight: Tokens.minTouchTarget)
                    .contentShape(Circle())
            }
            .accessibilityLabel("Change photo")
            .accessibilityIdentifier("profile.avatar")
            switch model.avatar {
            case .idle:
                EmptyView()
            case .uploading:
                Text("Uploading photo…").font(.footnote).foregroundStyle(Tokens.text2)
                    .accessibilityIdentifier("profile.avatar.status")
            case .queued:
                Text("Photo will upload when you're online").font(.footnote).foregroundStyle(Tokens.text2)
                    .accessibilityIdentifier("profile.avatar.status")
            }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var avatarImage: some View {
        if let preview = model.pickedPreview, let image = UIImage(data: preview) {
            Image(uiImage: image).resizable().scaledToFill()
        } else if let url = model.avatarImageURL {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    AvatarView(initials: ProfileView.initials(name), size: 96)
                }
            }
        } else {
            AvatarView(initials: ProfileView.initials(name), size: 96)
        }
    }

    private var nameSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Display name").font(.footnote).foregroundStyle(Tokens.text2)
            TextField("Display name", text: Binding(get: { model.nameDraft }, set: { model.nameDraft = $0 }))
                .textContentType(.name)
                .submitLabel(.done)
                .focused($nameFocused)
                .onSubmit {
                    model.commitName(model.nameDraft)
                    nameFocused = false
                }
                .padding(.horizontal, 12)
                .frame(minHeight: Tokens.minTouchTarget)
                .background(Tokens.surface, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                .overlay(RoundedRectangle(cornerRadius: Tokens.cardRadius).stroke(Tokens.line))
                .accessibilityIdentifier("profile.name")
        }
    }

    private var emailSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Email").font(.footnote).foregroundStyle(Tokens.text2)
            Text(model.email ?? "—")
                .foregroundStyle(Tokens.text)
                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
                .accessibilityIdentifier("profile.email")
        }
    }

    static func initials(_ displayName: String) -> String { MembersModel.initials(displayName) }
}

/// Raw bytes of whatever image the picker hands over; `downscaledJPEG` decodes and converts them.
struct PickedImage: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(importedContentType: .image) { PickedImage(data: $0) }
    }
}

extension ProfileView where Destination == EmptyView {
    public init(model: ProfileModel, isOnline: Bool) {
        self.init(model: model, isOnline: isOnline, notifications: { EmptyView() })
    }
}
