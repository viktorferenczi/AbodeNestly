import SwiftUI
import UIKit

/// DESIGN A3 colour tokens, light / dark.
public enum Tokens {
    public static let bg = color(light: 0xFAF8F5, dark: 0x121110)
    public static let surface = color(light: 0xFFFFFF, dark: 0x1C1A18)
    public static let surface2 = color(light: 0xF1EDE7, dark: 0x262320)
    public static let text = color(light: 0x1C1A18, dark: 0xF2EFEA)
    public static let text2 = color(light: 0x6B655E, dark: 0xA8A197)
    public static let line = color(light: 0xE4DFD7, dark: 0x2E2B27)
    public static let accent = color(light: 0x2F6B5A, dark: 0x6FB79F)
    public static let accentBg = color(light: 0xE3F0EB, dark: 0x1E3A32)
    public static let warn = color(light: 0xB8741A, dark: 0xE0A449)
    public static let danger = color(light: 0xB3402F, dark: 0xE06A58)

    /// 4-pt grid (DESIGN A5).
    public static let screenPadding: CGFloat = 16
    public static let cardRadius: CGFloat = 12
    public static let minTouchTarget: CGFloat = 48

    static func color(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}
