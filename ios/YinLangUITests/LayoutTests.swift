import XCTest

final class LayoutTests: XCTestCase {
    @MainActor private func launch(_ screen: String = "home", appearance: String = "light", template: String = "classic") -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["-ui-fixtures", "-ui-screen", screen, "-appearance", appearance, "-home-template", template, "-personal-accent", "original", "-personal-theme", "default"]; app.launch(); return app
    }
    @MainActor func testHomeAndSettings() throws {
        let app = launch()
        XCTAssertTrue(app.staticTexts["page-title-音浪"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["歌单 · 榜单 · 音乐库"].firstMatch.exists)
        XCTAssertEqual(app.navigationBars.count, 0)
        capture("home-light")
        app.buttons["总设置"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["page-title-总设置"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["个性化"].exists)
        capture("settings")
        app.buttons["返回"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["page-title-音浪"].waitForExistence(timeout: 5))
    }
    @MainActor func testLandscapeAndDarkHome() throws {
        let app = launch(appearance: "dark")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.staticTexts["歌单 · 榜单 · 音乐库"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertEqual(app.navigationBars.count, 0)
        XCTAssertFalse(app.buttons["显示边栏"].exists)
        capture("home-dark-landscape")
        XCUIDevice.shared.orientation = .portrait
    }
    @MainActor func testAndroidScreensAndTemplates() throws {
        XCUIDevice.shared.orientation = .portrait
        let screens = ["music","charts","playlists","playlist-detail","liked","library","discovery","songs","song-details","connection","books","book-discovery","book-detail","personalization","quality","cache","plugins","book-plugins","source-settings","downloads","server","about","effects","player","lyrics","queue","song-menu"]
        for screen in screens {
            let app = launch(screen)
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10), screen)
            if screen != "player" && screen != "lyrics" { XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "page-title-")).firstMatch.waitForExistence(timeout: 8), screen) }
            else { XCTAssertTrue(app.buttons["播放器菜单"].firstMatch.waitForExistence(timeout: 8)) }
            XCTAssertEqual(app.navigationBars.count, 0, screen)
            capture("android-" + screen)
            if screen == "personalization" {
                app.buttons["首页模板"].firstMatch.tap()
                XCTAssertTrue(app.staticTexts["选择喜欢的风格，返回首页即可看到。"].waitForExistence(timeout: 5))
                capture("android-template-picker")
            }
            if screen == "player" {
                app.buttons["显示歌词"].firstMatch.tap()
                XCTAssertTrue(app.staticTexts["风继续吹"].firstMatch.waitForExistence(timeout: 5))
                capture("android-player-lyrics")
            }
            app.terminate()
        }
        for template in ["gallery","radio"] {
            let app = launch(template: template)
            XCTAssertTrue(app.staticTexts["page-title-音浪"].waitForExistence(timeout: 10)); capture("home-" + template); app.terminate()
        }
    }
    @MainActor func testWideScreensKeepSameStructure() throws {
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        for screen in ["music","library","books","settings","player"] {
            let app = launch(screen, appearance: "dark")
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
            if screen == "player" { XCTAssertTrue(app.buttons["播放器菜单"].firstMatch.waitForExistence(timeout: 8)) }
            else { XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "page-title-")).firstMatch.waitForExistence(timeout: 8)) }
            XCTAssertEqual(app.navigationBars.count, 0); capture("wide-" + screen); app.terminate()
        }
    }
    @MainActor private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
}
