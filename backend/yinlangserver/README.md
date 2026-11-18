# yinlangserver

镜像基于 [LXServer v2.2.0 官方源码](https://github.com/XCQ0607/lxserver/releases/tag/v2.2.0)，提供 yinlangserver 网页播放器和管理台。同步、Subsonic 和音浪推送都可沿用容器端口 `9527`。

## 更新 DSM 上的 yinlangserver

使用 [compose.nas.yaml](compose.nas.yaml) 更新原项目。模板沿用服务名 `lxserver`、容器名 `yinlangserver`、端口 `9528:9527` 和 `/volume2/docker/yinlangserver` 的数据、日志、缓存目录。下载仍写入 `/volume2/HDD2/download`。

从 2.2.0-2 开始，缺少推送令牌或令牌短于 32 字符时，网页仍可启动，手机推送接口会返回配置提示。需要推送时，在原 Compose 项目设置 `DOWNLOAD_TOKEN`。可以在 DSM 用 `openssl rand -hex 32` 生成令牌，并把同一值填入音浪。启用网页访问密码时使用 `WEBPLAYER_PASSWORD` 和 `ENABLE_WEBPLAYER_AUTH=true`。

使用新版 Compose 模板时，请将已有 `.env.nas` 中的 `YINLANGSERVER_IMAGE` 改为 `tbslantian/yinlangserver:2.2.0-4`，再重新创建服务。若网页使用 LXServer 本地账号登录，将 `YINLANG_QUEUE_USER` 设为该账号名。

复制 `.env.nas.example` 为 `.env.nas` 并填好需要的设置后，在原 Compose 项目目录重新拉取并创建容器。

```sh
docker compose --env-file .env.nas -f compose.nas.yaml pull lxserver
docker compose --env-file .env.nas -f compose.nas.yaml up -d lxserver
```

## 从旧 LXServer 实例切换

原数据位于 `/volume2/docker/lxserver` 时，在 `.env.nas` 设置 `YINLANGSERVER_STORAGE_DIR=/volume2/docker/lxserver`；如已迁移到 `/volume2/docker/yinlangserver`，沿用模板默认值。

1. 在 NAS 检查原容器的版本。

   ```sh
   docker exec lx-sync-server node -p "require('/server/package.json').version"
   ```

   该镜像基于 LXServer 2.2.0。如果原容器版本更高，应先在复制出来的数据目录中验证兼容性。停止旧实例后备份 `/volume2/docker/lxserver/data`，再让新实例使用原数据。

2. 把 `.env.nas.example` 复制为 `.env.nas`。模板已填写镜像 `tbslantian/yinlangserver:2.2.0-4`。需要手机推送时，设置至少 32 字符的随机 `DOWNLOAD_TOKEN`。在停止旧实例前拉取镜像。

   ```sh
   docker compose -p yinlangserver --env-file .env.nas -f compose.nas.yaml pull
   ```

   默认继续读取原数据中的后台和网页播放器设置。若要用环境变量设置网页播放器密码，填写 `ENABLE_WEBPLAYER_AUTH=true` 和 `WEBPLAYER_PASSWORD`。LXServer 2.2.0 识别这两个变量。旧 Compose 中的 `FRONTEND_PASSWORD` 可移除。若依赖 Compose 创建或覆盖 `admin` 同步账号，将原来的同步密码填入 `LX_SYNC_ADMIN_PASSWORD`。

3. 停止旧容器。完成数据备份后，在迁移 Compose 所在目录启动新容器。切换时会有短暂中断，原网址和端口可继续使用。

   ```sh
   docker stop lx-sync-server
   docker compose -p yinlangserver --env-file .env.nas -f compose.nas.yaml up -d
   ```

   用原网址检查网页、同步和曲库，再在音浪测试推送。通过旧容器名访问的反向代理需要改用新容器名或 NAS 的 `9528` 端口。需要回退时，先停止新容器，再启动保留的旧容器。如果运行过版本更高的实例，应使用切换前的数据备份回退。

## 从源码包构建

解压完整部署包，进入 `backend/yinlangserver`。将 `.env.example` 复制为 `.env`，填写以下三项。

| 变量 | 用途 |
| --- | --- |
| `ADMIN_PASSWORD` | LXServer 后台登录密码 |
| `WEBPLAYER_PASSWORD` | LXServer 网页播放器访问密码 |
| `DOWNLOAD_TOKEN` | 音浪推送令牌，使用至少 32 个字符的随机值。仅使用网页时可留空 |
| `YINLANG_QUEUE_USER` | 推送任务所属的 LXServer 本地账号。游客队列使用默认值 `_open` |

```sh
docker compose up -d --build
```

首次构建需要拉取 Node 基础镜像并安装 LXServer 的 npm 依赖。若在 NAS 上本地构建并使用迁移模板，从解压后的根目录执行 `docker build -f backend/yinlangserver/Dockerfile -t yinlangserver:2.2.0-4 .`，将 `.env.nas` 的镜像设为 `yinlangserver:2.2.0-4`，再启动迁移 Compose。

LXServer 默认端口是 `9527`。已有 LXServer 实例时，可以在 `.env` 将 `LX_PORT` 改成空闲端口。通过 `LX_DATA_DIR`、`LX_CACHE_DIR` 和 `LX_MUSIC_DIR` 可以指定原实例的宿主机目录。迁移这些挂载时先备份，再停止原实例，使用新容器打开同一套数据。

## 手机设置

1. 在音浪打开「总设置 → 服务器下载」，启用推送。
2. 下载服务地址填写 `http://服务器局域网IP:9527`。按 NAS 模板部署时使用 `9528`。填写 `DOWNLOAD_TOKEN`。
3. 点「测试连接」并保存。在搜索结果的「⋯」里选择「推送到服务器下载」，或从下载菜单发起推送。在手机的「总设置 → 服务器任务」或网页的「下载管理」查看进度。

手机提交当前音源解析出的地址、请求头和所选音质，LXServer 原生队列处理下载。推送令牌授权将任务加入 `YINLANG_QUEUE_USER` 对应的队列。网页请切换到同一账号查看，使用默认 `_open` 时请使用游客状态。服务器任务中的“清除历史”只移除已结束的音浪推送任务记录，不会删除已经下载的音频文件；排队中、下载中和暂停任务会保留。临时地址已失效时，从手机重新推送。

## 文件位置

- 文件进入对应账号的 LXServer 原生下载目录，同时由 LXServer 写入「本地音乐」索引。使用默认的 `root` 存储位置时，NAS 上对应 `/volume2/HDD2/download/<账号>`，容器内对应 `/server/music/<账号>`。账号配置了 `data` 存储位置时，容器内对应 `/server/data/music/<账号>`。
- 升级前的推送文件保留在原目录。需要它们出现在原生下载队列时，可从手机重新推送。
- 如需接收局域网音乐库生成的音频链接，将该服务器的确切主机名或 IP 加入 `PRIVATE_MEDIA_HOSTS`，多个地址用逗号分隔。
