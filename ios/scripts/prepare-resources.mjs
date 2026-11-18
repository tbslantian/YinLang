import { cpSync, mkdirSync, existsSync, copyFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const root = fileURLToPath(new URL('../..', import.meta.url))
const app = path.join(root, 'ios/YinLang')
const runtime = path.join(app, 'Resources/Runtime')
mkdirSync(runtime, { recursive: true })
for (const name of ['plugin-host.js', 'catalog.bundle.js', 'catalog-licenses.txt']) {
  const source = path.join(root, 'shared', name)
  if (!existsSync(source)) throw Error(`Missing ${source}; run npm ci && npm run catalog first`)
  copyFileSync(source, path.join(runtime, name))
}
copyFileSync(path.join(root, 'app/src/main/assets/book-sources/Sources-v5.7.zip'), path.join(runtime, 'Sources-v5.7.zip'))
const assets = path.join(app, 'Resources/Assets.xcassets')
mkdirSync(assets, { recursive: true })
writeFileSync(path.join(assets, 'Contents.json'), JSON.stringify({info: {author:'xcode', version:1}}, null, 2))
for (const name of ['home_music_light.jpg','home_music_dark.jpg','home_books_light.jpg','home_books_dark.jpg','navidrome_logo.png','yinlang_neon.png']) {
  const destination = path.join(assets, `${path.parse(name).name}.imageset`)
  mkdirSync(destination, { recursive: true })
  copyFileSync(path.join(root, 'app/src/main/res/drawable-nodpi', name), path.join(destination, name))
  writeFileSync(path.join(destination, 'Contents.json'), JSON.stringify({images:[{filename:name, idiom:'universal'}], info:{author:'xcode', version:1}},null,2))
}
const icon = path.join(assets, 'AppIcon.appiconset')
mkdirSync(icon, {recursive: true})
const iconPNG = path.join(icon, 'AppIcon.png')
copyFileSync(path.join(root, 'app/src/main/res/drawable-nodpi/yinlang_neon.png'), iconPNG)
if (process.platform === 'darwin') execFileSync('sips', ['-z','1024','1024',iconPNG], {stdio:'ignore'})
else execFileSync('python', ['-c', 'from PIL import Image; import sys; p=sys.argv[1]; Image.open(p).convert("RGB").resize((1024,1024),Image.Resampling.LANCZOS).save(p)', iconPNG])
writeFileSync(path.join(icon,'Contents.json'),JSON.stringify({images:[{filename:'AppIcon.png',idiom:'universal',platform:'ios',size:'1024x1024'}],info:{author:'xcode',version:1}},null,2))
console.log('Prepared shared catalog, original Android artwork and book plugins for iOS.')
