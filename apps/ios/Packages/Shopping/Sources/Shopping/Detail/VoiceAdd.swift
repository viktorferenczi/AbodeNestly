import Foundation

/// What a spoken line came to: a name, with the quantity and unit when the person said them.
public struct SpokenItem: Equatable, Sendable {
    public var name: String
    public var quantity: Double?
    public var unit: String?

    public init(name: String, quantity: Double? = nil, unit: String? = nil) {
        self.name = name
        self.quantity = quantity
        self.unit = unit
    }
}

/// SHOP-14 parser. Web twin: `parseSpoken` in `web/src/shopping/VoiceAdd.ts` (DESIGN C6); keep the rules in step.
public enum VoiceAdd {
    /// Spoken unit forms, each mapped to a C3 preset, so a voice item lists the same as a typed one ("litres" is stored `l`).
    private static let unitWords: [String: String] = [
        "litre": "l", "litres": "l", "liter": "l", "liters": "l", "l": "l",
        "millilitre": "ml", "millilitres": "ml", "milliliter": "ml", "milliliters": "ml", "ml": "ml",
        "kilo": "kg", "kilos": "kg", "kilogram": "kg", "kilograms": "kg", "kg": "kg",
        "gram": "g", "grams": "g", "g": "g",
        "piece": "pcs", "pieces": "pcs", "pcs": "pcs",
        "pack": "pack", "packs": "pack", "packet": "pack", "packets": "pack",
        "bottle": "bottle", "bottles": "bottle",
        "can": "can", "cans": "can", "tin": "can", "tins": "can",
    ]

    /// One to twelve; "a" and "an" are one. "half" is handled apart because "half a kilo" has a filler word.
    private static let numberWords: [String: Double] = [
        "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
        "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
    ]

    /// Parses what the person said ("2 litres of milk"). Pure. `nil` when no name is left. Forms outside the rules
    /// ("one and a half") stay in the name. `locale` cases the text; none means the current locale.
    public static func parse(_ text: String, locale: Locale? = nil) -> SpokenItem? {
        let loc = locale ?? .current
        let spoken = text.lowercased(with: loc).split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        var quantity: Double?
        var rest = spoken

        if let digits = leadingNumber(spoken) {
            if digits.value > 0 {
                quantity = digits.value
                rest = digits.rest
            }
        } else {
            var words = spoken.split(separator: " ").map(String.init)
            let first = words.first
            let second = words.count > 1 ? words[1] : nil
            if first == "half" || (first == "a" && second == "half") {
                quantity = 0.5
                words.removeFirst(first == "half" ? 1 : 2)
                // "half a kilo", "half a lemon": the filler article goes with the quantity.
                if let head = words.first, head == "a" || head == "an", words.count > 1 { words.removeFirst() }
                rest = words.joined(separator: " ")
            } else if let first, let n = numberWords[first], words.count > 1 {
                quantity = n
                rest = words.dropFirst().joined(separator: " ")
            }
        }

        var unit: String?
        if quantity != nil {
            var words = rest.split(separator: " ").map(String.init)
            if let head = words.first, let mapped = unitWords[head], ItemSheetLogic.unitPresets.contains(mapped) {
                unit = mapped
                words.removeFirst()
                if words.first == "of" { words.removeFirst() }
            }
            rest = words.joined(separator: " ")
        }

        guard !rest.isEmpty else { return nil }
        return SpokenItem(name: String(rest.prefix(1)).uppercased(with: loc) + rest.dropFirst(), quantity: quantity, unit: unit)
    }

    /// A leading number: ASCII digits, then `.` or `,` and more digits. The comma is swapped for a point by hand, so the
    /// result never depends on the device's decimal mark (`Double("1,5")` is not reliable across Foundation versions).
    private static func leadingNumber(_ s: String) -> (value: Double, rest: String)? {
        func isDigit(_ c: Character) -> Bool { c >= "0" && c <= "9" }
        var chars = Substring(s)
        var number = ""
        while let c = chars.first, isDigit(c) {
            number.append(c)
            chars = chars.dropFirst()
        }
        guard !number.isEmpty else { return nil }
        if let mark = chars.first, mark == "." || mark == ",", let next = chars.dropFirst().first, isDigit(next) {
            number.append(".")
            chars = chars.dropFirst()
            while let c = chars.first, isDigit(c) {
                number.append(c)
                chars = chars.dropFirst()
            }
        }
        guard let value = Double(number) else { return nil }
        return (value, String(chars.drop(while: { $0.isWhitespace })))
    }
}
