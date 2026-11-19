# 音浪 iOS / iPadOS

原生 SwiftUI 通用应用，最低 iOS / iPadOS 17。iPhone 使用导航页面，iPad 使用可折叠侧栏，支持横竖屏和可调整窗口。首页默认经典影像，直接复用安卓版 `home_music_light/dark`、`home_books_light/dark` 原图。

## 构建

GitHub Actions 的 **iOS universal unsigned build** 在 macOS 上生成设备 archive、未签名 IPA，并在 iPhone / iPad 模拟器上运行测试。构建产物 `YinLang-iOS-unsigned` 包含 IPA、SHA256 校验值、Xcode archive、测试结果和日志。未签名 IPA 需要自行签名后安装，无需将证书或 Apple ID 写入仓库。

macOS 本地构建：

```sh
# 在仓库根目录，先按根 README 准备 .reference/lxserver-2.2.0
npm ci
npm run catalog
node ios/scripts/prepare-resources.mjs
python3 ios/scripts/generate-project.py
xcodebuild -project ios/YinLang.xcodeproj -scheme YinLang \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath ios/build/YinLang.xcarchive CODE_SIGNING_ALLOWED=NO archive
```

也可直接打开 `YinLang.xcodeproj`。源文件变动后重新运行项目生成脚本。生成的目录包、原图与官方听书源资源不重复提交；构建时从已有安卓/共享目录准备。

## 已接入

- 共享五平台目录：搜索、排行榜、歌单、分类、分页、关注及列表缓存。
- LX JavaScript 源文件/网址导入、启停、更新地址和手动更新；在 WKWebView 独立环境运行，通过原生 HTTP、CommonCrypto、Security 和 zlib 桥接。
- Navidrome/Subsonic 登录、发现、歌曲、专辑、艺术家、服务器收藏、歌单创建/编辑/删除/排序、加入歌曲及权限检查后移出歌曲、歌曲详情、歌词、转码配置。
- 官方随身听书 5.7 ZIP、用户 ZIP 导入、源发现/分类/搜索、书架、章节、源登录网页和设置、章节进度及按书倍速。
- AVPlayer 后台播放、锁屏信息/封面/遥控、系统 AirPlay、DLNA 发现/手动连接/转发/遥控、队列、四种播放顺序、同步歌词、方形/黑胶播放器、睡眠定时、倍速和系统音量。
- 原生音频处理：十段均衡器、与安卓相同的预设曲线、自定义预设、环境混响、干湿调节、独立升降调、耳机立体声环绕。
- 独立音乐/听书缓存、本机音乐下载及删除、yinlangserver 推送与任务查询。凭据使用 Keychain。

## 验收边界

iOS 使用系统 AirPlay。DLNA 转发支持当前在线文件和本机文件，HLS 不进行转码；自动发现需要签名时具有 Apple 的多播网络权限，未开通时可手动填写设备描述 XML 地址。`Multicast.entitlements` 提供相应配置参考，未签名构建不要求权限证书。音效通过本机音轨处理，HLS、受保护媒体和外部投送受系统输出路径限制。自动更新在进入前台时按周期执行，后台调度遵循 iOS 限制。音频格式取决于 iOS 解码能力。第三方音乐源、听书网站、登录验证码、Navidrome 实例及真实 AirPlay / DLNA 设备仍需使用实际账号与设备联调；模拟器编译和测试不能替代这些验收。
