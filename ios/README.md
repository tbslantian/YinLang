# 音浪 iOS / iPadOS

原生 SwiftUI 通用应用，最低 iOS / iPadOS 17。iPhone 使用导航页面，iPad 使用可折叠侧栏，支持横竖屏和可调整窗口。首页默认经典影像，直接复用安卓版 `home_music_light/dark`、`home_books_light/dark` 原图。

## 0.8.3 播放与音源名称修复

- 修正长音频会话与 `allowAirPlay` 的非法组合，解决进入播放器前的 `OSStatus -50`。使用 Apple 推荐的 `.playback / .default / .longFormAudio` 配置，继续支持系统 AirPlay。
- LX 源从脚本头读取 `@name`、`@version`，初始化时提供 `lx.currentScriptInfo`（包含作者、主页、说明和原脚本）。已安装源自动补回名称与版本，保留源 ID、启停状态和更新地址。
- 新增本地 HTTP + PCM 音频回归测试，分别经过 LX 解析链路及 Navidrome ping/stream 链路驱动 AVPlayer，验证播放时间实际前进和暂停/继续；iPhone、iPad 都运行这些测试。未签名模拟器直接配置测试账号，不依赖 Keychain 写入；该测试不替代具体第三方源、服务器与真机听测。

## 0.8.2 安装兼容修复

IPA 的 `CFBundleDisplayName` 改为 `YinLang`，与 `CFBundleName` 一起使用 ASCII 名称，避免部分 SideStore 版本注册 App ID 时把中文名称作为 `appIdName` 提交而遭拒。设备桌面名称显示为 YinLang，应用内部继续使用中文“音浪”。构建打包时检查两个名称字段，防止回归。仍需在实际 SideStore / Apple 账号上确认安装结果。

## 0.8.1 修复

- 五平台排行榜详情、首页榜首封面和已关注榜单更新改用 `bangid`，兼容旧收藏身份。
- 继续收听重新取得整本章节并定位上次章节，保留后续自动续播，兼容 0.8.0 进度。
- 分享链接直接打开歌单，不再自动改变关注；支持网易 fragment ID、分享文字中的链接及平台适配器短链解析。
- 音乐库随心听及随机专辑“全部”复用当前推荐快照，专辑后续分页从快照结束位置继续。
- 重新下载按所选档位重新解析，成功保存后替换旧下载；服务器推送不再被本地缓存截断。下载失败保留原文件。
- LX 解析与歌词补齐根字段 / `meta` 字段，内嵌歌词直接复用。

这些修复不等于完成全部安卓功能与 UI 对齐；其余差异见 [一致性审计](PARITY_AUDIT.md)。

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
