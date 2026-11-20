# iOS / Android 功能与 UI 一致性审计

审计日期：2026-11-19。

后续修订（0.8.7）：服务器歌单已加入与安卓一致的所有者分组、我的／共享／创建入口、原尺寸长按排序、取消恢复及歌曲多字段升降序；F12 的原始差异不能再当作当前实现。封面新增持久缓存，在线详情支持强制刷新。0.8.6 还已实现网络独立音质、实际音频参数、缓冲条和 3–5 线程完整缓存。此处保留原审计证据，具体版本验证见 `ios/README.md`，其余差异不随上述修改自动关闭。

UI 后续修订（0.8.4）：用户确认 iPhone/iPad 必须沿用安卓风格，iPad 只做宽屏适配。已移除 NavigationSplitView、大导航标题和 Form/List 系统分组样式；同步安卓色值、页面标题、卡片尺寸、图标与三套模板；播放器保持上下结构。个性化、Wi-Fi/移动网络音质、迷你条歌词、源设置控件、榜单卡片与书籍网格已同步实现。新增 Debug 固定数据截图覆盖主要页面及横竖屏，编译、测试和截图核验结果随发布记录提供。以下内容保留原始审计证据，不能视为新版的逐像素验收结论；未解决的其余功能差异也不因 UI 改版而自动关闭。

修复进展（iOS 0.8.1）：本报告原始基准下的 F01–F06 已完成代码修复，并加入排行榜、分享链接、章节恢复、LX 字段和重新下载的回归用例。排行榜保留旧收藏的身份，只转换 API 编号；章节恢复兼容旧 `_book` 记录；随心听和随机专辑“全部”复用当前快照。实际编译与测试结果以该修复提交的 Actions 为准。以下差异为原始审计记录，F07–F16 及其他 UI 缺项仍未全部对齐。

基准：仓库 `main`，提交 `d5d875e96a9c246e75660a9610a946539f625605`。已通过 `git ls-remote` 核对远程 `main` 与本地一致。Android 为 0.8.0（versionCode 36），iOS 为 0.8.0。

**结论：目前不一致，不能按“与最新安卓版功能、UI 完全相同”验收。** 首页经典模板使用相同原图，主要模块已建立，但存在阻断正常操作的缺陷、功能缺项及明显的界面实现差异。

本次进行两端源码对照，检查已有 Actions 日志、构建清单和模拟器截图，并实际运行共享目录 bundle 核对五平台排行榜编号。不包含本次重新编译、iOS 真机播放、真实账号登录或投送设备联调；没有完整的当前 Android 页面截图，未做逐像素双端截图比较。本次仅新增审计文档，没有修改或发布应用。

## 优先修复的功能问题

### F01 · P1 · 排行榜使用了错误的接口编号

- iOS：`YinLang/Core/Models.swift:51` 统一优先取 `id`，晚于它才取 `bangid`；`YinLang/UI/CatalogView.swift:134` 将 `item.key` 直接作为榜单详情接口的 `id`。音乐首页的榜首封面请求也优先取 `id`（`HomeView.swift:99` 附近）。
- Android：`app/src/main/java/app/soundshelf/MainActivity.java:1758` 对排行榜优先取 `bangid`，首页榜首封面同样如此（1127 行）。
- 共享层：`shared/catalog-api.js` 把该编号直接传给各平台 `charts.getList`，没有去掉前缀的转换。
- 实际在 Node VM 中运行当前 `shared/catalog.bundle.js` 的五平台 `charts` 操作，首条结果如下，未请求现网榜单详情：

| 平台 | iOS 当前选取的 key | 详情接口应使用的 bangid |
| --- | --- | --- |
| 网易 | `wy__19723756` | `19723756` |
| QQ | `tx__4` | `4` |
| 酷狗 | `kg__8888` | `8888` |
| 酷我 | `kw__93` | `93` |
| 咪咕 | `mg__27553319` | `27553319` |

影响：点击榜单会向接口提交错误参数，详情加载、榜单播放及相关封面获取可能失败。应按资源类型区分展示标识与接口标识，并覆盖五平台详情测试。

### F02 · P1 · “继续收听”把整本书变成单章节队列

- iOS：`YinLang/UI/BooksView.swift:19` 执行 `player.play([current])`。`Playback/Player.swift:74` 直接用传入数组替换队列。
- Android：`MainActivity.java:5070` 的 `resumeBook` 优先恢复当前播放，否则重新加载书籍详情和章节列表，并定位上次章节。
- 影响：iOS 能恢复当前章节的时间，但该章结束后无法顺序播放下一章；如果本来正在播放整本书，再点“继续收听”也会丢掉后续章节。
- 建议验收：至少三章的书，从第二章中间恢复，播完后自动进入第三章，保留按书倍速。

### F03 · P1 · “按链接打开”实际切换关注，并不打开详情

- iOS：`YinLang/UI/CatalogView.swift:94` 仅解析部分 query/path，随后在 102 行执行 `toggleFollow` 并提示已关注，没有跳转详情。
- Android：`MainActivity.java:1647` 直接打开 `onlineDetail`；聚合模式会先让用户选平台。
- 影响：重复输入同一链接会取消关注，但仍提示“已加入关注”；网易 `https://music.163.com/#/playlist?id=123` 的 ID 在 fragment 中，当前提取逻辑不能得到 `123`。分享文本、短链及各平台其他链接格式也缺少完整解析。
- 建议验收：五平台 ID/常见分享链接可直接打开，重复打开不改变关注状态。

### F04 · P2 · 音乐库“随心听 → 全部”没有显示当前推荐

- iOS：`YinLang/UI/LibraryView.swift:79` 跳入 `kind: "songs"`；167 行执行 `nav.search("")`，没有传递当前 `getRandomSongs` 结果。
- Android：`MainActivity.java:3008` 保存当前推荐快照，3250 行起的 `libraryDiscoveryList` 使用该快照。
- 影响：从随机推荐点“全部”会变成普通歌曲列表，可能与刚看到、刚播放的推荐完全不同。随机专辑“全部”也重新获取随机数据。

### F05 · P2 · 改音质后重新下载可能仍保存旧文件

- iOS：`YinLang/AppStore.swift:148` 的解析先返回同歌曲的已下载文件；208 行下载复用此解析，229 行文件名只按歌曲 ID 生成，已存在目标不覆盖。
- Android：`MediaSources.java:387`、414 行起提供独立下载解析路径，在线音乐按指定档位尝试，不通过 iOS 这种已下载文件优先路径。
- 触发：已有某首歌的 128k 下载，改为 FLAC 后再次下载同一首歌。iOS 可能直接复用原下载而报告成功，无法完成升级。

### F06 · P2 · LX 音源入参兼容层未同步

- Android：`MediaSources.java:78` 的 `onlineMusicInfo` 同步补齐根字段与 `meta`，包括 `songId`、`albumName`、`qualitys/_qualitys` 等；解析和歌词均使用该结果。
- iOS：`YinLang/AppStore.swift:192` 将 `candidate.raw` 直接交给 `lxResolve`；204 行歌词同样直接使用原始字段。`Models.swift:55` 仅序列化原始数据。
- 当前共享目录返回的歌曲主要是根字段结构；`shared/plugin-host.js:293` 也不会补 `meta`。
- 影响为条件性兼容风险：读取 `musicInfo.meta` 的音源可能失败；不能由此断言所有音源均不可用。本次未对具体用户音源做现网验收。

## 其他功能缺项与行为差异

| 编号 | 模块 | Android 当前行为 | iOS 当前差异与证据 |
| --- | --- | --- | --- |
| F07 | 听书搜索 | 可聚合全部已启用源，并发分批搜索 | `UI/BooksView.swift:36` 起只有单源选择，没有“全部听书源”；Android `MainActivity.java:4560` 附近支持聚合 |
| F08 | 听书发现 | 保留菜单/分类层级，按源和版本保存浏览内容、位置，切源/离页取消请求 | `BooksView.swift:89` 附近递归展平菜单；只用局部状态和 generation 忽略过期结果，未实现相同的浏览缓存和请求取消流程 |
| F09 | 听书插件管理 | 同一插件包内可单独启停每个源；设置读取已保存值，支持 select/password/number 等 | `UI/SettingsView.swift:163` 的源设置没有单源启停；values 从空字典开始，没有读回已存值；除布尔外均为普通 TextField，缺下拉选择、密码遮罩等。`AppStore.bookSources` 未按源 enabled 状态过滤 |
| F10 | 书籍章节 | 可先加载部分章节，再加载全部，并恢复上次章节 | `BooksView.swift:139` 一次请求全部章节，详情仅有“从第一章播放”；缺渐进加载与详情继续收听入口 |
| F11 | Navidrome 推荐 | 当前账号缓存推荐、返回复用；歌曲/专辑推荐偏好；最近添加歌曲专页 | `UI/LibraryView.swift:65` 起推荐仅为视图内状态，缺推荐偏好；没有 Android `MainActivity.java:3757` 起的“最近添加歌曲”专页 |
| F12 | 服务器歌单 | 长按拖动、边缘自动滚动、取消恢复；排序可包含日期等 | `LibraryView.swift:39` 使用菜单向上/向下移动；歌曲列表只有原始/名称/艺术家排序，没有拖动及日期排序 |
| F13 | 音乐库音质 | Wi-Fi、移动网络独立设置码率 | `SettingsView.swift:64` 起只有一组 `nav-bitrate`；`Core/Navidrome.swift:42` 统一使用它。Android 见 `LibraryAudioSettings.java:10` 起 |
| F14 | 歌曲查看菜单 | 播放器也提供关联专辑、艺术家、详情；歌曲详情可查看歌词 | iOS `PlayerView.playerMenu` 缺关联导航；`UI/Components.swift:36` 歌曲菜单有详情/专辑但缺直接艺术家入口；`LibraryView.swift:217` 详情缺查看歌词入口 |
| F15 | 自动更新 | 默认不计费网络更新，覆盖关注、默认/已浏览列表，Android 可后台执行 | `AppStore.swift:105` 只刷新关注列表及查询服务器歌单；无同样网络限制和默认/已浏览列表覆盖；进入前台才检查。iOS 调度方式可以不同，但更新范围和网络偏好仍未对齐 |
| F16 | 缓存与播放器进度 | Range 三路预缓存、分片复用及缓存覆盖条 | `Core/Persistence.swift:47` 使用完整文件额外下载；`Player.swift:177` 虽记录 buffered，`UI/PlayerView.swift:96` 仅系统 Slider，未绘制缓存覆盖条 |

F07–F16 为 P2 功能一致性缺项；是否优先于其他工作应按用户常用流程决定。

## UI 与操作一致性

| 页面/操作 | 结论 | 依据 |
| --- | --- | --- |
| 默认经典首页图片 | 同源 | `scripts/prepare-resources.mjs:19` 直接复制 Android 四张首页 JPG；本次查看了 iPhone 浅色、iPad 深色横屏截图 |
| 默认首页布局风格 | 大体相同，细节不同 | 两端均为浅色上下横幅、深色并排卡片；iOS 使用原生大导航标题，卡片高度上限、间距、圆角、裁剪规则与 Android 不同，不能判为逐像素一致 |
| 画廊/电台模板 | 不一致 | `UI/HomeView.swift:45` 起用渐变和 SF Symbols；Android `MainActivity.java:802` 起使用自绘入口艺术和不同排版。自动轮换按天的计算基准也不同，不能保证同一天选中相同模板 |
| 总设置/连接页 | 不一致 | iOS 为原生 Form/Picker；Android 为自绘分组卡片/弹窗。连接页 iOS 填完整 URL，Android 分主机、端口、路径、HTTPS 等输入 |
| 个性化 | 有缺项 | Android 支持默认/透明主题、强调色、迷你播放条关闭/单行/双行歌词、推荐类型；iOS SettingsView 和固定 Palette 未提供对应选项 |
| 在线排行榜封面 | 未对齐 | `UI/CatalogView.swift:48` 只展示 Artwork，未对无图榜单触发逐榜 `chart-cover`；Android `MainActivity.java:1847` 起按可见性加载榜首缩略图。首页单独请求不能覆盖排行榜页 |
| 歌曲行 | 未对齐 | `UI/Components.swift:23` 起主要展示标题、歌手、封面、菜单，未呈现 Android 对应的音质/时长信息布局 |
| 歌词交互 | 缺手势 | iOS `UI/PlayerView.swift:64` 起仅按钮切换/滚动/点歌词跳转；没有 Android 的左右滑动切歌词、轻点进入全屏隐藏上下控制栏 |
| 循环与明暗 | 操作/记忆不同 | iOS 循环按钮直接弹 Menu，明暗为 `@State dark = true`；Android 单击循环切换、长按选模式，明暗存入 `player-dark` 偏好 |
| 播放状态与进度 | 信息不同 | iOS 有音源/解析档位状态，但未接入 Android 同等的音频参数展示和缓存覆盖条 |
| iPad | 已有适配，验收不足 | `YinLangApp.swift:49` 附近使用 NavigationSplitView，播放器大横屏双列；已有首页/设置截图，但没有完整功能页、分屏尺寸、动态字体、键盘和长列表验收证据 |

## 平台差异与尚未验证项

- iOS 使用系统 AirPlay 选择器，Android 为应用内发现/配对流程；可实现相同投送目标，但界面及权限机制不相同。DLNA 已有发现、手动连接、转发和遥控代码，实际可用性需设备验证。
- 音效预设曲线复用并不代表听感完全一致：Android 音效路径和 iOS AudioTap/C DSP 不同，需相同音频、相同参数的听测及信号测量。
- AVPlayer 与 Android 解码链支持的媒体格式可能不同；后台播放、断网缓存、耳机/蓝牙、锁屏、长时播放、真实插件 HTTP/Cookie 登录均未在本次做双端实测。
- 未签名 IPA 能生成、归档可成功，不代表未经签名即可完成真机安装和联调。

## 已有编译与测试证据

对应构建：[GitHub Actions 44426248221](https://github.com/tbslantian/YinLang/actions/runs/44426248221)，提交与本次基准一致。

- 本地 `build/ios/build-verification.json` 记录 0.8.0、iPhone/iPad device family `[1, 2]`、最低 iOS 17、未签名 IPA。
- `.reference/final-build.log` 记录设备 Archive 成功、DSP 检查通过、9 项核心测试通过，以及 iPhone 和 iPad 各 2 项 UI 测试通过。
- `YinLangUITests/LayoutTests.swift` 仅断言首页导航标题/字幕、进入设置以及深色横屏首页，并保存截图；没有检查排行榜详情、书籍连续播放、分享链接、服务器推荐、音源兼容或 Android 视觉对照。
- `YinLangTests/CoreTests.swift` 覆盖播放模式边界、歌词解析、权限、匹配、认证、压缩/加密、运行桥和本地 HTTP 等，不等于现网功能通过。

因此，“编译通过、有限测试通过”成立；“功能和 UI 与 Android 全部一致”不成立。建议先修 F01–F03，再补 F04–F16 和页面细节；之后使用同一数据集在 Android、iPhone、iPad 上做完整操作及截图回归。
