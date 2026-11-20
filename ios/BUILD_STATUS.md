# iOS 构建记录

## 0.8.10（2026-11-20）

最终源码 `2f264d0d917612853ba550b7e30619e9a668703a`，版本 0.8.10、构建号 13。[设备编译任务](https://github.com/tbslantian/YinLang/actions/runs/44683773311)成功，沿用用户要求跳过全部模拟器与音频 DSP 测试，未做真机验收。

大播放器及常驻播放条使用用户参考图制作的透明小猫 8 帧循环：播放时迈步并沿进度前进，暂停保留当前姿势，主播放器拖动／点按定位和 VoiceOver 调整保留。动画在独立 Core Animation 图层运行，页面不逐帧刷新；后台、离开窗口和减弱动态效果时停止迈步。原图、作者脚本和动态设计预览已保存，预览不代表设备运行结果。

IPA 已核验来源提交、版本、设备族 `[1,2]`、未签名状态与 SHA256 `71aa7ce8388275386316f8df2a9bf0d26b986a1468a47e554e86836e2e52dad6`，公开直链回读一致。[0.8.10 Release](https://github.com/tbslantian/YinLang/releases/tag/ios-v0.8.10)。

## 0.8.9（2026-11-20）

最终源码 `935590facaf0184005935e8ce73ff380f3c7d133`，版本 0.8.9、构建号 12。[设备编译任务](https://github.com/tbslantian/YinLang/actions/runs/44681261438)成功，按用户要求跳过全部模拟器与音频 DSP 测试，未做真机验收或帧率测量。

恢复全屏播放器解析、验证、成功与缓存通知，与常驻播放条共用状态与计时。常驻播放条封面上的播放／暂停图标点击后显示 2 秒并隐藏，再次点击重新计时。Navidrome 不发布解析类通知，缓存完成保留。

补充咪咕榜单封面元数据与旧分类封面，绕过旧空缓存；移除平台已返回空 columnInfo 的欧美旧榜单。在线榜单按平台批量取封面并合并重复请求；图片后台解码、限制最大像素并保留磁盘原图，缓存键复用，磁盘清理节流，目录页面不再直接观察播放进度。保留 UI 风格。

IPA 已核验来源提交、版本、设备族 `[1,2]`、未签名状态与 SHA256 `f1758b547fc17135992a695ff149e2cd6909b6b83dfab3da90b84bb8ce8342fa`。公开直链已回读核对同一校验值。[0.8.9 Release](https://github.com/tbslantian/YinLang/releases/tag/ios-v0.8.9)。

## 0.8.8（2026-11-20）

最终源码 `664405cef91cef4b857d2b06e4f51e220ee2ddd0`，版本 0.8.8、构建号 11。[设备编译任务](https://github.com/tbslantian/YinLang/actions/runs/44672348148)成功，按用户要求跳过全部模拟器及音频 DSP 测试，未做真机验收。

正在解析／验证及成功、缓存、失败通知均改在常驻播放条中间显示；移除页面和全屏播放器独立解析浮层。Navidrome 原曲及下一首准备不发布解析类提示，缓存完成和真实错误反馈保留。

IPA 已核验来源提交、版本、设备族 `[1,2]`、未签名状态与 SHA256 `20da492e703e951df89266ae75cf10514a583bd8f4359558ccc47fa8ac3ad60a`。[0.8.8 Release](https://github.com/tbslantian/YinLang/releases/tag/ios-v0.8.8)。

## 0.8.7（2026-11-20）

最终代码 `4ec2dee8f636010283da467841d002575c0830ca`，版本 0.8.7、构建号 10。[只编译的最终任务](https://github.com/tbslantian/YinLang/actions/runs/44664256264)成功，按用户最后要求跳过全部模拟器测试。

包含 Navidrome 拖动松手后禁止进入、我的／共享／创建歌单入口、账号顺序持久化、安卓歌曲排序选项、封面内存与磁盘缓存、预解析复用不重复提示、榜单强制刷新。成功文字通知移至常驻播放条中间；修复无损码率缺失，显示格式、实际 kbps 与 kHz，缺系统码率时从文件长度和实际时长计算平均值。

较早提交 `41dbb30` 的手机 24 项基础、20 项播放及 5 项本轮 UI 全部通过。平板同轮除预解析等待超时、拖动边缘滚动距离不足外均通过；预解析专项在 `b699554` 通过。随后修改拖动实时触点及定时滚动，按用户要求取消余下测试；最终通知和无损码率修改也未进行测试。不得将早期通过结果当作最终版完整验收，未进行真机或真实服务验收。

IPA 已核验版本、设备族 `[1,2]`、无签名、来源提交与 SHA256 `d7547ab9e59c21435cdbf096b4cccf8793efbc2c3566363b494c6e022894a801`。[0.8.7 Release](https://github.com/tbslantian/YinLang/releases/tag/ios-v0.8.7)。以下保留 0.8.6 历史记录。

## 最终结果（2026-11-20）

最终源码为 `9d15d40e6ddefeb9b7dd3570d4b011622f8a1ce4`。GitHub 原先失败在 UI 测试，设备编译与基础/播放测试通过。修复了迷你条容器覆盖子按钮标识及点击区域、开关和菜单测试定位、横屏封面无障碍边界、歌单手势挂载与连续触摸，以及 iPad 导航手势拦截长按的问题。

最终 [iPhone 构建与两项专项回归](https://github.com/tbslantian/YinLang/actions/runs/44644313955)通过；最终 [iPad 原尺寸拖动、连续换位、边缘滚动、持久化专项](https://github.com/tbslantian/YinLang/actions/runs/44644308334)通过。手机/平板的迷你条、预解析与预缓存开关、播放器菜单已在 `c125c5a` 专项通过；平板横屏已在 `24c97c1` 通过；最初整套回归的 21 项基础和 20 项播放/缓存用例全部通过。最终源码未重新跑全部测试，未进行真机验收。

0.8.6 build 9 未签名 IPA 校验通过，支持设备族 `[1,2]`，SHA256 为 `09f3d91fada78572daae615a5b74509fc7f35f51ebcdd152a3a41f09565c124b`。发布地址：[0.8.6 Release](https://github.com/tbslantian/YinLang/releases/tag/ios-v0.8.6)。以下为先前重启交接历史。

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
