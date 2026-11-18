import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const publicDir = path.join(process.argv[2] || '/source-code', 'public')
const brand = 'yinlangserver'
const replacements = {
  'index.html': [
    ['LX Music Sync Server - 管理控制台', brand + ' - 管理控制台'],
    ['LX Sync Server', brand],
    ['<h2>LX Sync</h2>', '<h2>' + brand + '</h2>'],
    ['placeholder="LX Music Web"', 'placeholder="' + brand + '"'],
  ],
  'app.js': [['LX Music Web', brand]],
  'filemanager.html': [['文件管理 · LX Music', '文件管理 · ' + brand]],
  'about.md': [
    ['>LX Music Sync Server</h1>', '>' + brand + '</h1>'],
    ['自己的音乐服务器，', '基于 LX Music Sync Server 的音乐服务器，'],
  ],
  'music/index.html': [
    ['<title>LX Music Web</title>', '<title>' + brand + '</title>'],
    ['>LX MUSIC</h1>', '>' + brand + '</h1>'],
    ['欢迎使用 LX Music Web 播放器', '欢迎使用 ' + brand + ' 播放器'],
  ],
  'music/login.html': [['LX Music Web', brand]],
  'sw.js': [['lx-sync-server-v5', 'lx-sync-server-yinlangserver-v1']],
  'music/sw.js': [['lx-music-web-v65', 'lx-music-web-yinlangserver-v2']],
}

for (const [name, changes] of Object.entries(replacements)) {
  const filename = path.join(publicDir, name)
  let content = await readFile(filename, 'utf8')
  for (const [original, branded] of changes) {
    if (!content.includes(original)) throw Error(`Unexpected branding in ${name}: ${original}`)
    content = content.replaceAll(original, branded)
  }
  await writeFile(filename, content)
}

for (const name of ['manifest.json', 'music/manifest.json']) {
  const filename = path.join(publicDir, name)
  const manifest = JSON.parse(await readFile(filename, 'utf8'))
  manifest.name = brand
  manifest.short_name = brand
  await writeFile(filename, JSON.stringify(manifest, null, 4) + '\n')
}
