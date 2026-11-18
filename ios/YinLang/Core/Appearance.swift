import SwiftUI

enum Appearance {
    static func scheme(_ mode: String, date: Date) -> ColorScheme? {
        if mode == "light" { return .light }; if mode == "dark" { return .dark }
        guard mode == "scheduled" else { return nil }
        let defaults = UserDefaults.standard
        let light = defaults.object(forKey: "light-minute") as? Int ?? 420, dark = defaults.object(forKey: "dark-minute") as? Int ?? 1140
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date), minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let day = light <= dark ? minute >= light && minute < dark : minute >= light || minute < dark
        return day ? .light : .dark
    }
}
