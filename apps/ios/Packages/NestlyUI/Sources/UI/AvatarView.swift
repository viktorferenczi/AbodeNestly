import SwiftUI

/// Initials in a circle. Loading `avatar_path` images is out of scope for CORE-04, so this is also the fallback
/// once that lands. The caller computes the initials; the name is read next to the avatar, so VoiceOver skips it.
public struct AvatarView: View {
    private let initials: String
    private let size: CGFloat

    public init(initials: String, size: CGFloat = 40) {
        self.initials = initials
        self.size = size
    }

    public var body: some View {
        Text(initials)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundStyle(Tokens.accent)
            .frame(width: size, height: size)
            .background(Tokens.accentBg, in: Circle())
            .accessibilityHidden(true)
            .accessibilityIdentifier("avatar")
    }
}
