import XCTest
import UIKit

final class LayoutTests: XCTestCase {
    @MainActor private func launch(_ screen: String = "home", appearance: String = "light", template: String = "classic", extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["-ui-fixtures", "-ui-screen", screen, "-appearance", appearance, "-home-template", template, "-personal-accent", "original", "-personal-theme", "default"] + extraArguments; app.launch(); return app
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
            if screen == "books" { assertBooksDiscoveryLayout(app) }
            if screen == "library" { assertLibraryNavigationLayout(app) }
            capture("android-" + screen)
            if screen == "personalization" {
                app.buttons["首页模板"].firstMatch.tap()
                XCTAssertTrue(app.staticTexts["选择喜欢的风格，返回首页即可看到。"].waitForExistence(timeout: 5))
                capture("android-template-picker")
            }
            if screen == "player" {
                let cover = app.descendants(matching: .any).matching(identifier: "player-artwork-stage").firstMatch
                XCTAssertTrue(cover.exists)
                cover.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).press(forDuration: 0.05, thenDragTo: cover.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)))
                let lyricStage = app.descendants(matching: .any).matching(identifier: "player-lyrics-stage").firstMatch
                XCTAssertTrue(lyricStage.waitForExistence(timeout: 5))
                lyricStage.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).press(forDuration: 0.05, thenDragTo: lyricStage.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)))
                XCTAssertTrue(cover.waitForExistence(timeout: 5))
                app.buttons["显示歌词"].firstMatch.tap()
                XCTAssertTrue(app.buttons["风继续吹"].firstMatch.waitForExistence(timeout: 5))
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
            XCTAssertEqual(app.navigationBars.count, 0)
            if screen == "books" { assertBooksDiscoveryLayout(app) }
            if screen == "library" { assertLibraryNavigationLayout(app) }
            capture("wide-" + screen); app.terminate()
        }
    }
    @MainActor func testFivePlatformButtonsRemainTappable() throws {
        for screen in ["charts", "playlists"] {
            let app = launch(screen)
            for source in ["tx", "kg", "kw", "mg", "wy", "mg", "tx", "wy"] {
                let chip = app.buttons["chip-" + source].firstMatch
                XCTAssertTrue(chip.waitForExistence(timeout: 5))
                let chips = app.scrollViews["android-chips"].firstMatch
                for _ in 0..<3 {
                    if chip.isHittable, chip.frame.minX >= chips.frame.minX, chip.frame.maxX <= chips.frame.maxX { break }
                    if chip.frame.minX < chips.frame.minX { chips.swipeRight() }
                    else { chips.swipeLeft() }
                }
                chip.tap()
                let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: chip)
                XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 3), .completed, source)
            }
            app.terminate()
        }
    }
    @MainActor func testPlayerRotationPlacesArtworkBesideLyricsAndControls() throws {
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = launch("player")
        XCTAssertEqual(app.staticTexts["player-audio-info"].firstMatch.label, "FLAC · 960 kbps · 96 kHz")
        let portrait = app.descendants(matching: .any).matching(identifier: "player-artwork-stage").firstMatch
        XCTAssertTrue(portrait.waitForExistence(timeout: 8))
        XCUIDevice.shared.orientation = .landscapeLeft
        let artwork = app.descendants(matching: .any).matching(identifier: "player-landscape-artwork").firstMatch
        let lyrics = app.descendants(matching: .any).matching(identifier: "player-landscape-lyrics").firstMatch
        let controls = app.descendants(matching: .any).matching(identifier: "player-landscape-controls").firstMatch
        XCTAssertTrue(artwork.waitForExistence(timeout: 8)); XCTAssertTrue(lyrics.exists); XCTAssertTrue(controls.exists)
        let positioned = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            artwork.frame.maxX <= lyrics.frame.minX && lyrics.frame.maxY <= controls.frame.minY + 1 && lyrics.frame.height > 32
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [positioned], timeout: 5), .completed)
        XCTAssertTrue(app.buttons["下一首"].isHittable)
        if app.buttons["显示旋转唱片"].exists { app.buttons["显示旋转唱片"].tap() }
        XCTAssertTrue(app.buttons["显示封面"].waitForExistence(timeout: 5))
        capture("player-landscape-vinyl-lyrics")
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(portrait.waitForExistence(timeout: 8))
        app.buttons["显示歌词"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "player-lyrics-stage").firstMatch.waitForExistence(timeout: 5))
    }
    @MainActor func testMiniPlayerLeavesBottomActionsReachableAcrossMusicAndBooks() throws {
        XCUIDevice.shared.orientation = .portrait
        let cases = [
            ("music", "catalog-card-wy:playlist:playlist-4"),
            ("playlist-detail", "load-more"),
            ("library", "library-playlist-open-playlist-4"),
            ("book-discovery", "book-tile-fixture-source:book:book-8"),
            ("book-detail", "book-chapter-11"),
            ("books", "book-tile-fixture-source:book:book-4")
        ]
        for (screen, identifier) in cases {
            var flags = ["-ui-mini-player", "-ui-bottom-audit", "-mini-lyrics", "double", "-catalog-lists-grid", "YES"]
            if screen == "playlist-detail" { flags.append("-ui-mini-loading") }
            let app = launch(screen, extraArguments: flags)
            if screen == "music" { app.buttons["music-playlists-card"].firstMatch.tap() }
            let mini = app.descendants(matching: .any).matching(identifier: "mini-player").firstMatch
            XCTAssertTrue(mini.waitForExistence(timeout: 8))
            let target = app.buttons[identifier].firstMatch
            for _ in 0..<12 {
                if target.exists, target.isHittable, target.frame.maxY <= mini.frame.minY - 2 { break }
                app.scrollViews.firstMatch.swipeUp()
            }
            XCTAssertTrue(target.exists, screen); XCTAssertTrue(target.isHittable, screen)
            XCTAssertLessThanOrEqual(target.frame.maxY, mini.frame.minY - 2, screen)
            if identifier == "load-more" {
                target.tap()
                let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: target)
                XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 5), .completed)
            }
            capture("bottom-actions-" + screen); app.terminate()
        }
    }
    @MainActor func testBookDiscoveryRemembersSourceAcrossAppLaunches() throws {
        var app = launch("book-discovery", extraArguments: ["-ui-multiple-book-sources"])
        let picker = app.buttons["book-source-picker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 8))
        picker.tap()
        let second = app.buttons.matching(NSPredicate(format: "label ENDSWITH %@", "听书 · 备用听书源")).firstMatch
        XCTAssertTrue(second.waitForExistence(timeout: 5)); second.tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "备用听书源"), object: picker)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed)
        app.terminate()
        app = launch("book-discovery", extraArguments: ["-ui-multiple-book-sources"])
        let restored = app.buttons["book-source-picker"].firstMatch
        let remembered = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "备用听书源"), object: restored)
        XCTAssertEqual(XCTWaiter.wait(for: [remembered], timeout: 8), .completed)
        app.terminate()
        app = launch("book-discovery")
        let fallback = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "有声书精选"), object: app.buttons["book-source-picker"].firstMatch)
        XCTAssertEqual(XCTWaiter.wait(for: [fallback], timeout: 8), .completed)
        app.terminate()
    }
    @MainActor func testChartsAndPlaylistsStyleButtonsAndColumnSelection() throws {
        XCUIDevice.shared.orientation = .portrait
        for screen in ["charts", "playlists"] {
            let app = launch(screen)
            let toggle = app.buttons["catalog-style-toggle"].firstMatch
            let columns = app.buttons["catalog-columns"].firstMatch
            XCTAssertTrue(toggle.waitForExistence(timeout: 8)); XCTAssertGreaterThanOrEqual(toggle.frame.width, 44); XCTAssertGreaterThanOrEqual(toggle.frame.height, 44)
            if toggle.value as? String == "列表显示" { toggle.tap() }
            // Exercise the edge of the button, beyond the small icon itself.
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5)).tap()
            XCTAssertEqual(toggle.value as? String, "列表显示"); XCTAssertFalse(columns.isEnabled)
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            XCTAssertEqual(toggle.value as? String, "卡片显示"); XCTAssertTrue(columns.isEnabled)
            columns.tap()
            XCTAssertTrue(app.staticTexts["page-title-卡片列数"].waitForExistence(timeout: 5))
            if UIDevice.current.userInterfaceIdiom == .phone { XCTAssertFalse(app.buttons["4 列"].exists) }
            app.buttons.matching(NSPredicate(format: "label ENDSWITH %@", "1 列")).firstMatch.tap()
            let oneColumn = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "1 列"), object: columns)
            XCTAssertEqual(XCTWaiter.wait(for: [oneColumn], timeout: 5), .completed)
            columns.tap(); app.buttons["2 列"].tap()
            let twoColumns = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "2 列"), object: columns)
            XCTAssertEqual(XCTWaiter.wait(for: [twoColumns], timeout: 5), .completed)
            columns.tap()
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "自动（")).firstMatch.tap()
            capture(screen + "-display-controls")
            app.terminate()
        }
    }
    @MainActor func testLibraryPlaylistOpensFromEmptyRowArea() throws {
        let app = launch("library")
        let row = app.buttons["library-playlist-open-playlist-1"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["page-title-华语经典"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["风继续吹 · 1"].firstMatch.waitForExistence(timeout: 5))
    }
    @MainActor func testQueueRanksAndSwipeRemoval() throws {
        let app = launch("queue")
        XCTAssertTrue(app.staticTexts["page-title-播放队列（4）"].waitForExistence(timeout: 8))
        XCTAssertEqual(app.staticTexts["song-rank-1"].firstMatch.label, "1")
        XCTAssertEqual(app.staticTexts["song-rank-4"].firstMatch.label, "4")
        let row = app.descendants(matching: .any).matching(identifier: "queue-row-1").firstMatch
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).press(forDuration: 0.05, thenDragTo: row.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)))
        let remove = app.buttons["queue-remove-1"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 5)); remove.tap()
        XCTAssertTrue(app.staticTexts["page-title-播放队列（3）"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["风继续吹 · 2"].exists)
        XCTAssertTrue(app.staticTexts["风继续吹 · 1"].exists)
        XCTAssertEqual(app.staticTexts["song-rank-1"].firstMatch.label, "1")
        XCTAssertFalse(app.staticTexts["song-rank-4"].exists)
    }
    @MainActor private func assertLibraryNavigationLayout(_ app: XCUIApplication) {
        let count = app.staticTexts["library-song-count"].firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 5))
        let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "私人音乐库 · 8 首歌曲"), object: count)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 5), .completed)
        let hero = app.buttons["library-discovery"].firstMatch
        XCTAssertTrue(hero.exists); XCTAssertGreaterThanOrEqual(hero.frame.height, 232 - 0.5)
        let songs = app.buttons["library-songs-shortcut"].firstMatch
        let albums = app.buttons["library-albums-shortcut"].firstMatch
        let favorites = app.descendants(matching: .any).matching(identifier: "library-favorites-shortcut").firstMatch
        for shortcut in [songs, albums, favorites] {
            XCTAssertTrue(shortcut.exists); XCTAssertGreaterThanOrEqual(shortcut.frame.height, 72 - 0.5)
            XCTAssertGreaterThanOrEqual(shortcut.frame.minX, hero.frame.maxX)
            XCTAssertLessThanOrEqual(shortcut.frame.maxY, hero.frame.maxY + 1)
        }
        XCTAssertEqual(hero.frame.height, songs.frame.height + albums.frame.height + favorites.frame.height + 16, accuracy: 1)
    }
    @MainActor private func assertBooksDiscoveryLayout(_ app: XCUIApplication) {
        let hero = app.buttons["books-discovery"].firstMatch
        XCTAssertTrue(hero.waitForExistence(timeout: 5))
        XCTAssertEqual(hero.frame.height, 184, accuracy: 1)
        let favorites = app.staticTexts["我的收藏"].firstMatch
        XCTAssertTrue(favorites.exists)
        XCTAssertLessThanOrEqual(hero.frame.maxY, favorites.frame.minY)
        var previousBottom = hero.frame.minY
        for identifier in ["books-shelf-shortcut", "books-plugins-shortcut", "books-settings-shortcut"] {
            let shortcut = app.buttons[identifier].firstMatch
            XCTAssertTrue(shortcut.exists, identifier)
            XCTAssertEqual(shortcut.frame.height, 56, accuracy: 1, identifier)
            XCTAssertGreaterThanOrEqual(shortcut.frame.minY, previousBottom - 1, identifier)
            XCTAssertLessThanOrEqual(shortcut.frame.maxY, hero.frame.maxY + 1, identifier)
            XCTAssertGreaterThanOrEqual(shortcut.frame.minX, hero.frame.maxX, identifier)
            previousBottom = shortcut.frame.maxY
        }
    }
    @MainActor private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
}
