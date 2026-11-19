import Contract
import PhotosUI
import SwiftUI
import UI
import UniformTypeIdentifiers

/// The shared attachment control (CORE-11) that pantry item, log entry and asset screens host. `enabled` is the
/// entity's module flag: off renders nothing (SPEC §9 X7).
public struct AttachmentPickerView: View {
    let model: AttachmentPicker
    let enabled: Bool
    @State private var photoItem: PhotosPickerItem?
    @State private var choosingPDF = false

    public init(model: AttachmentPicker, enabled: Bool = true) {
        self.model = model
        self.enabled = enabled
    }

    public var body: some View {
        if enabled {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("Add photo", systemImage: "photo")
                            .frame(minHeight: Tokens.minTouchTarget)
                    }
                    .accessibilityIdentifier("attachments.photo")
                    Button {
                        choosingPDF = true
                    } label: {
                        Label("Add PDF", systemImage: "doc")
                            .frame(minHeight: Tokens.minTouchTarget)
                    }
                    .accessibilityIdentifier("attachments.pdf")
                }
                .disabled(model.state == .uploading)
                status
                list
            }
            .task { await model.start() }
            .task { await model.observe() }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    await pickPhoto(item)
                    photoItem = nil
                }
            }
            .fileImporter(isPresented: $choosingPDF, allowedContentTypes: [.pdf]) { result in
                guard case .success(let url) = result else { return }
                Task { await pickPDF(url) }
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch model.state {
        case .idle:
            Color.clear.frame(height: 0).accessibilityIdentifier("attachments.idle")
        case .uploading:
            Text("Uploading…").font(.footnote).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("attachments.uploading")
        case .queued:
            Text("Will upload when you're online").font(.footnote).foregroundStyle(Tokens.text2)
                .accessibilityIdentifier("attachments.queued")
        case .error(let message):
            HStack {
                Text(message).font(.footnote).foregroundStyle(Tokens.danger)
                    .accessibilityIdentifier("attachments.error")
                Button("Retry") { Task { await model.retry() } }
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityIdentifier("attachments.retry")
            }
        }
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(model.attachments) { attachment in
                row(for: attachment).accessibilityIdentifier("attachments.item")
            }
            ForEach(model.queued) { upload in
                Text(upload.filename).font(.body)
                    .accessibilityIdentifier("attachments.queuedItem")
            }
        }
    }

    @ViewBuilder
    private func row(for attachment: Attachment) -> some View {
        if attachment.mime.hasPrefix("image/") {
            if let url = model.thumbnails[attachment.id] {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        Tokens.surface2
                    }
                }
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: Tokens.cardRadius))
            } else {
                Tokens.surface2
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: Tokens.cardRadius))
            }
        } else {
            Text(attachment.storagePath.split(separator: "/").last.map(String.init) ?? attachment.storagePath)
                .font(.body)
        }
    }

    private func pickPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            await model.pick(data: Data(), filename: "photo", mime: "image/jpeg")
            return
        }
        let type = item.supportedContentTypes.first { $0.conforms(to: .image) }
        let mime = type?.preferredMIMEType ?? "image/jpeg"
        let ext = type?.preferredFilenameExtension ?? "jpg"
        await model.pick(data: data, filename: "photo-\(UUID().uuidString.prefix(8).lowercased()).\(ext)", mime: mime)
    }

    private func pickPDF(_ url: URL) async {
        // The importer hands over a security-scoped URL.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            await model.pick(data: Data(), filename: url.lastPathComponent, mime: "application/pdf")
            return
        }
        await model.pick(data: data, filename: url.lastPathComponent, mime: "application/pdf")
    }
}
