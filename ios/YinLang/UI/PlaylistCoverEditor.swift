import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct PlaylistCoverEditor: View {
    @EnvironmentObject private var store: AppStore
    let item: MediaItem
    let editable: Bool
    @Binding var busy: Bool
    @State private var currentItem: MediaItem?
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var filePicker = false
    @State private var pendingCover: Data?
    @State private var coverError = ""
    @State private var coverNotice = ""
    @State private var removeCover = false
    @State private var coverSelection = UUID()
    var body: some View {
        AndroidSection("歌单封面") {
            HStack(spacing: 16) {
                if let pendingCover, let image = UIImage(data: pendingCover) {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: 112, height: 112).clipShape(RoundedRectangle(cornerRadius: 17))
                } else { Artwork(item: currentItem ?? item, size: 112) }
                VStack(alignment: .leading, spacing: 8) {
                    Text("上传后会覆盖 Navidrome 服务端的歌单封面，其他设备也会显示新封面。").font(.system(size: 13)).foregroundStyle(Palette.muted)
                    Text("图片居中裁成方形。需服务器支持封面上传，并具有歌单修改权限。").font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
            }
            PhotosPicker(selection: $selectedPhoto, matching: .images) { Label("从照片选择封面", systemImage: "photo") }.disabled(!editable || busy)
            Button { filePicker = true } label: { Label("从文件选择封面", systemImage: "folder") }.disabled(!editable || busy)
            if pendingCover != nil {
                Button("上传并覆盖服务器封面") { Task { await changeCover(remove: false) } }.buttonStyle(AndroidButtonStyle(primary: true)).disabled(!editable || busy).accessibilityIdentifier("playlist-cover-upload")
                Button("取消所选图片") { coverSelection = UUID(); pendingCover = nil; selectedPhoto = nil }.disabled(busy)
            }
            Button("移除服务器自定义封面", role: .destructive) { removeCover = true }.disabled(!editable || busy).accessibilityIdentifier("playlist-cover-remove")
            if busy { ProgressView("正在处理封面…") }
            if !coverNotice.isEmpty { Text(coverNotice).font(.system(size: 13)).foregroundStyle(Palette.accent) }
            if !coverError.isEmpty { Text(coverError).font(.system(size: 13)).foregroundStyle(Palette.accent) }
        }
            .confirmationDialog("移除服务端自定义封面？将恢复服务器自动生成的歌单封面。", isPresented: $removeCover, titleVisibility: .visible) { Button("移除自定义封面", role: .destructive) { Task { await changeCover(remove: true) } } }
            .onChange(of: selectedPhoto) { _, selection in
                guard let selection else { return }
                let token = UUID(); coverSelection = token; busy = true; coverError = ""; coverNotice = ""
                Task {
                    do {
                        guard let data = try await selection.loadTransferable(type: Data.self) else { throw AppFailure.message("未能读取所选照片") }
                        let jpeg = try await Task.detached(priority: .userInitiated) { try PlaylistCoverImage.prepare(data) }.value
                        guard coverSelection == token else { return }; pendingCover = jpeg
                    } catch { if coverSelection == token { coverError = error.localizedDescription } }
                    if coverSelection == token { busy = false }
                }
            }
            .fileImporter(isPresented: $filePicker, allowedContentTypes: [.image]) { result in
                let token = UUID(); coverSelection = token; busy = true; coverError = ""; coverNotice = ""
                Task {
                    do {
                        let url = try result.get()
                        let jpeg = try await Task.detached(priority: .userInitiated) {
                            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                            let bytes = (try url.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
                            guard bytes <= 25 * 1_048_576 else { throw AppFailure.message("图片超过 25 MB，请选择较小的文件") }
                            return try PlaylistCoverImage.prepare(Data(contentsOf: url))
                        }.value
                        guard coverSelection == token else { return }; pendingCover = jpeg
                    } catch { if coverSelection == token { coverError = error.localizedDescription } }
                    if coverSelection == token { busy = false }
                }
            }
    }
    private func changeCover(remove: Bool) async {
        guard editable, !busy, remove || pendingCover != nil else { return }
        busy = true; coverError = ""; coverNotice = ""; defer { busy = false }
        do {
            currentItem = try await store.changePlaylistCover(currentItem ?? item, jpeg: remove ? nil : pendingCover)
            pendingCover = nil; selectedPhoto = nil
            coverNotice = remove ? "已移除服务器自定义封面" : "已覆盖 Navidrome 服务器封面"
        } catch { coverError = error.localizedDescription }
    }
}
