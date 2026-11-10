import SwiftUI

/// DESIGN A6 OfflineBanner: shown on screens that need the network.
public struct OfflineBanner: View {
    private let message: String

    public init(message: String) {
        self.message = message
    }

    public var body: some View {
        Label(message, systemImage: "wifi.slash")
            .font(.subheadline)
            .foregroundStyle(Tokens.warn)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
            .accessibilityIdentifier("offline-banner")
    }
}
