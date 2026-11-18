import XCTest

final class LayoutTests: XCTestCase {
    @MainActor func testHomeAndSettings() throws {
        let app = XCUIApplication(); app.launchArguments = ["-appearance", "light", "-home-template", "classic"]; app.launch()
        XCTAssertTrue(app.navigationBars["音浪"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["歌单 · 榜单 · 音乐库"].firstMatch.exists)
        capture("home-light")
        app.buttons["总设置"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["总设置"].waitForExistence(timeout: 5))
        capture("settings")
    }
    @MainActor func testLandscapeAndDarkHome() throws {
        let app = XCUIApplication(); app.launchArguments = ["-appearance", "dark", "-home-template", "classic"]; app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.staticTexts["歌单 · 榜单 · 音乐库"].firstMatch.waitForExistence(timeout: 10))
        capture("home-dark-landscape")
        XCUIDevice.shared.orientation = .portrait
    }
    @MainActor private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
}
