# 0.8.6 构建交接记录

2026-11-20：用户准备重启安装 macOS，记录本轮进度。下面是交接时的状态，不代表最终测试结果。

构建源码：`0644927f7f40cfccc4429246dd18ec22bbbd6c37`，版本 0.8.6，构建号 9。该提交包含此前的播放器、通知、缓存、70% 下一首准备及 3–5 缓存线程修改；本次新增 Navidrome 歌单原尺寸长按拖动、实时换位、上下边缘滚动、排序持久化，并修复缓存丢失扩展名后无法离线重播的问题。

交接时三路任务均已完成通用设备 Release archive 与未签名 IPA 打包，正在执行模拟器测试：

| 任务 | GitHub Actions |
| --- | --- |
| iPhone 全套：基础、播放和 UI | [44561731079](https://github.com/tbslantian/YinLang/actions/runs/44561731079) |
| iPad 全套：播放和 UI | [44561746540](https://github.com/tbslantian/YinLang/actions/runs/44561746540) |
| iPad 长按排序专项 | [44562668352](https://github.com/tbslantian/YinLang/actions/runs/44562668352) |

排序专项用例为 `YinLangUITests/LayoutTests/testLibraryPlaylistLongPressKeepsSizeReordersAndRestores`，通过真实长按拖动检查全程尺寸、连续换位、边缘滚动以及重启后的顺序。新增离线用例会停止 HTTP 服务后播放缓存；3/5 线程用例会逐字节检查合并后的音频及共享并发上限。当前尚未确认这些测试通过，也尚未发布 0.8.6 Release。未进行真机验收。

继续时先检查上述任务日志与截图，修复实际失败后再生成并发布最终包。最新源码有变动则重新构建；工作流支持 `test_device` 选择手机/平板及 `test_filter` 指定 XCTest 用例。发布前核验 IPA 的版本、构建号、来源提交、SHA256、未签名状态及 `UIDeviceFamily=[1,2]`，交付 Release 中的 IPA 直接下载链接。

现有 Windows 工作区的 `.git/` 中保存了本地构建 API、提取和发布脚本：`ios-build-api.py`、`fetch-ios-ipa.py`、`verify-ios-package-086.py`、`publish-ios-release-086.py` 和本轮 Release 说明。它们不会随克隆复制，认证从 Git 凭据在进程内读取。不要将凭据写入仓库。预定 Release 标签为 `ios-v0.8.6`，IPA 名称为 `YinLang-0.8.6-unsigned.ipa`。

安卓工作树的既有未提交修改未包含在本轮 iOS 提交中，仍保留在原 Windows 工作目录。macOS 本地编译资源准备与 Xcode 命令见 [README](README.md#构建)，云端使用 Xcode 16.4。
