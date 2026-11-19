import SwiftUI

struct LibraryView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    @State private var playlists: [MediaItem] = []
    @State private var error = ""
    @State private var create = false
    @State private var name = ""
    private var orderKey: String { "playlist-order:" + (store.nav?.identity ?? "") }
    var body: some View {
        AndroidForm {
            if store.nav == nil {
                NavigationLink { ConnectionView() } label: { ModuleCard(title: "连接音乐库", subtitle: "浏览服务器上的歌曲、专辑和歌单", icon: "server.rack", color: Color(rgb: 0x2d4e76)) }.buttonStyle(.plain)
                NavigationLink("添加服务器") { ConnectionView() }.buttonStyle(AndroidButtonStyle(primary: true))
            } else {
                Text(UserDefaults.standard.string(forKey: "nav-alias").flatMap { $0.isEmpty ? nil : $0 } ?? "Navidrome").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.muted)
                NavigationLink { CatalogView(mode: "search", library: true) } label: { SearchEntry(title: "搜索资料库") }.buttonStyle(.plain)
                GeometryReader { proxy in
                    HStack(spacing: 10) {
                        NavigationLink { LibraryDiscoveryView() } label: { DiscoveryHero() }.frame(width: (proxy.size.width - 10) * 1.3 / 2.3)
                        VStack(spacing: 8) {
                            smallLink("歌曲", icon: "music.note", kind: "songs")
                            smallLink("专辑", icon: "opticaldisc", kind: "albums")
                            HStack(spacing: 0) { NavigationLink { LibraryListView(kind: "favorites", title: "我喜欢的") } label: { LibraryShortcut(title: "我喜欢的", icon: "heart") }; Button { Task { if let nav = store.nav, let songs = try? await nav.get("getStarred2").object("starred2").rows("song"), !songs.isEmpty { player.play(songs.map { store.libraryItem($0) }) } } } label: { YLIcon(systemName: "play.fill", size: 16).foregroundStyle(Palette.accent).frame(width: 38, height: 56) }.accessibilityLabel("播放服务器收藏") }.androidSurface(radius: 18)
                        }
                    }
                }.frame(height: 184).buttonStyle(.plain)
                HStack { SectionTitle(title: "我的歌单"); Button { create = true } label: { YLIcon(systemName: "plus").frame(width: 44, height: 44) }.accessibilityLabel("创建服务器歌单") }
                if !error.isEmpty { ErrorPanel(text: error) { Task { await load() } } }
                if playlists.isEmpty && error.isEmpty { Text("还没有服务器歌单").font(.system(size: 13)).foregroundStyle(Palette.muted) }
                ForEach(playlists) { item in
                    HStack(spacing: 0) {
                        NavigationLink { LibraryListView(kind: "playlist", identifier: item.key, title: item.title) } label: { HStack(spacing: 12) { Artwork(item: item); VStack(alignment: .leading, spacing: 5) { Text(item.title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink); Text("\(item.raw.string("songCount")) 首歌曲").font(.system(size: 13)).foregroundStyle(Palette.muted) }; Spacer() }.padding(11) }
                        NavigationLink { PlaylistEditor(item: item) } label: { YLIcon(systemName: "ellipsis", size: 19).frame(width: 48, height: 48) }.accessibilityLabel("歌单操作")
                    }.androidSurface().buttonStyle(.plain).draggable(item.key).dropDestination(for: String.self) { keys, _ in guard let key = keys.first, let from = playlists.firstIndex(where: { $0.key == key }), let to = playlists.firstIndex(of: item) else { return false }; playlists.move(fromOffsets: IndexSet(integer: from), toOffset: to > from ? to + 1 : to); UserDefaults.standard.set(playlists.map(\.key), forKey: orderKey); return true }
                }
            }
        }.androidPage("音乐库", category: "音乐") { NavigationLink { ConnectionView() } label: { Image("navidrome_logo").resizable().scaledToFit().frame(width: 34, height: 34).frame(width: 48, height: 48) }.accessibilityLabel("音乐库连接与音质设置") }
            .task(id: store.connectionRevision) { await load() }.refreshable { await load() }
            .alert("创建歌单", isPresented: $create) { TextField("歌单名称", text: $name); Button("创建") { Task { do { guard let nav = store.nav, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }; _ = try await nav.get("createPlaylist", [.init(name: "name", value: name)]); name = ""; await load() } catch { store.message = error.localizedDescription } } }; Button("取消", role: .cancel) {} }
    }
    private func smallLink(_ title: String, icon: String, kind: String) -> some View { NavigationLink { LibraryListView(kind: kind, title: title) } label: { LibraryShortcut(title: title, icon: icon) } }
    private func load() async {
        guard let nav = store.nav else { playlists = []; return }; error = ""
        do {
            let rows = try await nav.get("getPlaylists").object("playlists").rows("playlist").map { store.libraryItem($0, kind: "playlist") }
            let order = UserDefaults.standard.stringArray(forKey: orderKey) ?? []
            playlists = rows.sorted { (order.firstIndex(of: $0.key) ?? Int.max) < (order.firstIndex(of: $1.key) ?? Int.max) }
        } catch { self.error = error.localizedDescription }
    }
    private func move(_ item: MediaItem, offset: Int) { guard let index = playlists.firstIndex(of: item), playlists.indices.contains(index + offset) else { return }; playlists.swapAt(index, index + offset); UserDefaults.standard.set(playlists.map(\.key), forKey: orderKey) }
}

struct LibraryDiscoveryView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    @State private var sections: [String: [MediaItem]] = [:]
    @State private var failures: [String: String] = [:]
    @State private var loading: Set<String> = []
    @AppStorage("discover-kind") private var discover = "songs"
    private let kinds = ["songs", "frequent", "random", "newest"]
    private func title(_ key: String) -> String { ["songs": "随心听", "frequent": "常听专辑", "random": "随机推荐", "newest": "最近添加"][key] ?? key }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                ForEach(kinds, id: \.self) { key in
                    VStack(alignment: .leading, spacing: 12) {
                        HStack { Text(title(key)).font(.title3.bold()); Spacer(); Button { Task { await load(key) } } label: { YLIcon(systemName: "arrow.clockwise") }.disabled(loading.contains(key)).accessibilityLabel("刷新" + title(key))
                            NavigationLink("全部") { LibraryListView(kind: key == "songs" && discover == "songs" ? "discoverySongs" : "albums", identifier: key == "songs" ? "random" : key, title: title(key), snapshot: sections[key]) }.font(.caption).disabled(sections[key]?.isEmpty != false)
                        }
                        if loading.contains(key) && sections[key] == nil { ProgressView() }
                        if let error = failures[key] { ErrorPanel(text: error) { Task { await load(key) } } }
                        if key == "songs" && discover == "songs" {
                            if let rows = sections[key], !rows.isEmpty {
                                Button("播放全部", systemImage: "play.fill") { player.play(rows) }.font(.subheadline)
                                ForEach(rows.prefix(6)) { item in SongRow(item: item) { player.play(rows, at: rows.firstIndex(of: item) ?? 0) } }
                            }
                        } else if key == "frequent" {
                            ForEach(Array((sections[key] ?? []).prefix(5).enumerated()), id: \.offset) { index, item in NavigationLink { LibraryListView(kind: "album", identifier: item.key, title: item.title) } label: { HStack { Text(String(index + 1)).font(.system(size: 12, weight: .bold)).foregroundStyle(Palette.muted).frame(width: 24); MediaRow(item: item) } }.buttonStyle(.plain) }
                        } else {
                            ScrollView(.horizontal, showsIndicators: false) { LazyHStack(alignment: .top, spacing: 14) {
                                ForEach(sections[key] ?? []) { item in NavigationLink { LibraryListView(kind: "album", identifier: item.key, title: item.title) } label: { VStack(alignment: .leading, spacing: 8) { Artwork(item: item, size: 145); Text(item.title).font(.subheadline).lineLimit(1).foregroundStyle(Palette.ink); Text(item.subtitle).font(.caption).lineLimit(1).foregroundStyle(Palette.muted) }.frame(width: 145) } }
                            } }
                            if sections[key]?.isEmpty == true { Text(key == "frequent" ? "还没有常听记录，多听几张专辑再回来看看。" : "音乐库里暂时没有内容。").font(.caption).foregroundStyle(Palette.muted) }
                        }
                    }
                }
            }.padding(16).frame(maxWidth: .infinity).frame(maxWidth: .infinity)
        }.background { AndroidBackground() }.androidPage("发现").task { for key in kinds where sections[key] == nil { await load(key) } }.refreshable { for key in kinds { await load(key) } }
    }
    private func load(_ key: String) async {
        guard let nav = store.nav, !loading.contains(key) else { return }; loading.insert(key); failures[key] = nil; defer { loading.remove(key) }
        do {
            let rows: [JSONObject]
            if key == "songs" && discover == "songs" { rows = try await nav.get("getRandomSongs", [.init(name: "size", value: "12")]).object("randomSongs").rows("song") }
            else { rows = try await nav.get("getAlbumList2", [.init(name: "type", value: key == "songs" ? "random" : key), .init(name: "size", value: "12")]).object("albumList2").rows("album") }
            sections[key] = rows.map { store.libraryItem($0, kind: key == "songs" && discover == "songs" ? "song" : "album") }
        } catch { failures[key] = error.localizedDescription }
    }
}

struct LibraryListView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var player: Player
    var kind: String
    var identifier: String = ""
    var title: String
    var snapshot: [MediaItem]? = nil
    @State private var snapshotConsumed = false
    @State private var rows: [MediaItem] = []
    @State private var original: [MediaItem] = []
    @State private var editable = false
    @State private var loading = false
    @State private var error = ""
    @State private var query = ""
    @State private var offset = 0
    @State private var more = false
    @State private var sort = "original"
    @State private var removeIndex: Int?
    var isSongs: Bool { ["songs", "discoverySongs", "favorites", "album", "playlist"].contains(kind) }
    var displayed: [MediaItem] {
        let filtered = query.isEmpty ? rows : rows.filter { ($0.title + $0.subtitle).localizedCaseInsensitiveContains(query) }
        if sort == "name" { return filtered.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending } }
        if sort == "artist" { return filtered.sorted { $0.subtitle.localizedStandardCompare($1.subtitle) == .orderedAscending } }
        return filtered
    }
    var body: some View {
        AndroidList {
            AndroidSearch(text: $query, hint: "在列表中搜索")
            HStack { if isSongs { AndroidChoice(title: "排序", selection: $sort, options: [("original", "原始顺序"),("name", "歌曲名称"),("artist", "艺术家")]) }; Spacer(); Button { Task { await load() } } label: { YLIcon(systemName: "arrow.clockwise", size: 19).frame(width: 44, height: 44) }.accessibilityLabel("刷新歌单") }

            if store.nav == nil { NavigationLink("连接音乐库") { ConnectionView() } }
            if isSongs && !rows.isEmpty { Button("播放全部（\(displayed.count)）", systemImage: "play.fill") { player.play(displayed) } }
            ForEach(Array(displayed.enumerated()), id: \.offset) { position, item in
                if isSongs {
                    SongRow(item: item, remove: kind == "playlist" && editable ? { if sort == "original" && query.isEmpty { removeIndex = position } else { store.message = "请先恢复原始顺序并清除搜索，再移出歌曲" } } : nil) { player.play(displayed, at: position) }
                        .swipeActions { if kind == "playlist" && editable { Button("移出歌单", role: .destructive) {
                            if sort == "original" && query.isEmpty { removeIndex = position }
                            else { store.message = "请先恢复原始顺序并清除搜索，再移出歌曲" }
                        } } }
                } else {
                    NavigationLink { LibraryListView(kind: kind == "artists" ? "artist" : kind == "playlists" ? "playlist" : "album", identifier: item.key, title: item.title) } label: { MediaRow(item: item) }
                }
            }
            if loading { ProgressView().frame(maxWidth: .infinity) }
            if !error.isEmpty { ErrorPanel(text: error) { Task { await load() } } }
            if more && !loading { Button("加载更多") { Task { await load(next: true) } } }
            if !loading && rows.isEmpty && error.isEmpty && store.nav != nil { ErrorPanel(text: "音乐库里暂无内容") }
        }.androidPage(title, category: "音乐库")
.task { if rows.isEmpty { await load() } }.refreshable { await load() }
            .confirmationDialog("从歌单中移出这首歌？", isPresented: Binding(get: { removeIndex != nil }, set: { if !$0 { removeIndex = nil } }), titleVisibility: .visible) {
                Button("移出歌单", role: .destructive) { let position = removeIndex; removeIndex = nil; Task { guard let position, let nav = store.nav else { return }; do { try await nav.remove(identifier, expected: original, position: position); await load() } catch { store.message = error.localizedDescription } } }
            } message: { Text("歌曲文件会保留在音乐库中。") }
    }
    private func load(next: Bool = false) async {
        guard let nav = store.nav, !loading else { return }; loading = true; error = ""; defer { loading = false }
        if !next, let snapshot, !snapshotConsumed {
            guard snapshot.allSatisfy({ $0.raw.string("_account") == nav.identity }) else { rows = []; original = []; more = false; error = "音乐库账号已变更，请返回发现页刷新"; return }
            rows = snapshot; original = snapshot; offset = snapshot.count
            snapshotConsumed = true
            more = kind == "albums" && identifier != "random" && snapshot.count == 12
            return
        }
        do {
            let requested = next ? offset : 0
            let result: [JSONObject]
            switch kind {
            case "discoverySongs": result = try await nav.get("getRandomSongs", [.init(name: "size", value: "12")]).object("randomSongs").rows("song")
            case "songs": result = try await nav.search("", offset: requested)
            case "favorites": result = try await nav.get("getStarred2").object("starred2").rows("song")
            case "albums": result = try await nav.get("getAlbumList2", [.init(name: "type", value: ["frequent", "random", "newest"].contains(identifier) ? identifier : "newest"), .init(name: "size", value: "50"), .init(name: "offset", value: "\(requested)")]).object("albumList2").rows("album")
            case "album": result = try await nav.get("getAlbum", [.init(name: "id", value: identifier)]).object("album").rows("song")
            case "artist": result = try await nav.get("getArtist", [.init(name: "id", value: identifier)]).object("artist").rows("album")
            case "artists": result = try await nav.get("getArtists").object("artists").rows("index").flatMap { $0.rows("artist") }
            case "playlists": result = try await nav.get("getPlaylists").object("playlists").rows("playlist")
            case "playlist": let playlist = try await nav.playlist(identifier); editable = nav.canEdit(playlist); result = playlist.rows("entry")
            default: result = []
            }
            let items = result.map { store.libraryItem($0, kind: isSongs ? "song" : "album") }
            if next { rows += items } else { rows = items }; original = rows; offset = requested + result.count; more = ["songs", "albums"].contains(kind) && result.count == 50
        } catch { self.error = error.localizedDescription }
    }
}

struct PlaylistEditor: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) var dismiss
    let item: MediaItem
    @State private var name = ""
    @State private var comment = ""
    @State private var shared = false
    @State private var editable = false
    @State private var confirm = false
    var body: some View {
        AndroidForm {
            TextField("名称", text: $name); TextField("简介", text: $comment, axis: .vertical); Toggle("公开歌单", isOn: $shared)
            Button("保存") { Task { do { guard let nav = store.nav, editable, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }; _ = try await nav.get("updatePlaylist", [.init(name: "playlistId", value: item.key), .init(name: "name", value: name), .init(name: "comment", value: comment), .init(name: "public", value: String(shared))]); dismiss() } catch { store.message = error.localizedDescription } } }.disabled(!editable)
            Button("删除歌单", role: .destructive) { confirm = true }.disabled(!editable)
        }.androidPage("编辑歌单").task {
            guard let nav = store.nav else { return }; do { let playlist = try await nav.playlist(item.key); name = playlist.string("name"); comment = playlist.string("comment"); shared = playlist["public"] as? Bool ?? false; editable = nav.canEdit(playlist) } catch { store.message = error.localizedDescription }
        }.confirmationDialog("删除此歌单？歌曲文件会保留。", isPresented: $confirm, titleVisibility: .visible) { Button("删除歌单", role: .destructive) { Task { do { guard let nav = store.nav, editable else { return }; _ = try await nav.get("deletePlaylist", [.init(name: "id", value: item.key)]); dismiss() } catch { store.message = error.localizedDescription } } } }
    }
}

struct AddToPlaylistView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) var dismiss
    var item: MediaItem
    @State private var rows: [MediaItem] = []
    @State private var create = false
    @State private var name = ""
    var body: some View {
        AndroidList { Button("新建歌单并加入", systemImage: "plus") { create = true }; ForEach(rows) { row in Button(row.title) { Task { do { guard let nav = store.nav else { return }; let latest = try await nav.playlist(row.key); guard nav.canEdit(latest) else { throw AppFailure.message("没有修改此歌单的权限") }; _ = try await nav.get("updatePlaylist", [.init(name: "playlistId", value: row.key), .init(name: "songIdToAdd", value: item.key)]); dismiss() } catch { store.message = error.localizedDescription } } } } }
            .androidPage("加入歌单").task { guard let nav = store.nav else { return }; do { rows = try await nav.get("getPlaylists").object("playlists").rows("playlist").filter { $0.string("owner").caseInsensitiveCompare(nav.user) == .orderedSame }.map { store.libraryItem($0, kind: "playlist") } } catch { store.message = error.localizedDescription } }
            .alert("新建歌单", isPresented: $create) { TextField("名称", text: $name); Button("创建并加入") { Task { do { guard let nav = store.nav, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return }; _ = try await nav.get("createPlaylist", [.init(name: "name", value: name), .init(name: "songId", value: item.key)]); dismiss() } catch { store.message = error.localizedDescription } } }; Button("取消", role: .cancel) {} }
    }
}

struct SongDetailsView: View {
    @EnvironmentObject var store: AppStore
    var item: MediaItem
    @State private var raw: JSONObject = [:]
    var body: some View {
        AndroidList {
            AndroidSection("基础信息") { field("歌曲", "title"); field("艺术家", "artist"); field("专辑", "album"); field("时长（秒）", "duration"); field("音轨", "track"); field("碟号", "discNumber") }
            AndroidSection("扩展信息") { field("格式", "suffix"); field("码率（kbps）", "bitRate"); field("采样率（Hz）", "samplingRate"); field("位深", "bitDepth"); field("声道", "channelCount"); field("播放次数", "playCount"); field("最后播放", "played"); field("文件路径", "path"); field("文件大小", "size") }
            AndroidSection("回放增益") { ForEach(["trackGain", "albumGain", "trackPeak", "albumPeak"], id: \.self) { key in LabeledContent(key, value: raw.object("replayGain").string(key).isEmpty ? "无数据" : raw.object("replayGain").string(key)) } }
            AndroidSection("关联") {
                if !raw.string("albumId").isEmpty { NavigationLink("专辑 · " + raw.string("album")) { LibraryListView(kind: "album", identifier: raw.string("albumId"), title: raw.string("album")) } }
                ForEach(Array(raw.rows("artists").enumerated()), id: \.offset) { _, artist in NavigationLink("艺术家 · " + artist.string("name")) { LibraryListView(kind: "artist", identifier: artist.string("id"), title: artist.string("name")) } }
                if raw.rows("artists").isEmpty && !raw.string("artistId").isEmpty { NavigationLink("艺术家 · " + raw.string("artist")) { LibraryListView(kind: "artist", identifier: raw.string("artistId"), title: raw.string("artist")) } }
            }
        }.androidPage("歌曲详情").task { raw = item.raw; if let nav = store.nav, let song = try? await nav.get("getSong", [.init(name: "id", value: item.key)]).object("song"), song.string("id") == item.key { raw.merge(song) { _, new in new } } }
    }
    private func field(_ title: String, _ key: String) -> some View { LabeledContent(title, value: raw.string(key).isEmpty ? "无数据" : raw.string(key)).textSelection(.enabled) }
}
