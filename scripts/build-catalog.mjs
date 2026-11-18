import { build } from 'esbuild'
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('..', import.meta.url))
const sources = path.resolve(process.env.LXSERVER_SOURCE || path.join(root, '.reference/lxserver-2.2.0'))
const sdk = path.join(sources, 'src/modules/utils/musicSdk')
if (!existsSync(sdk)) throw Error('Extract lxserver v2.2.0 into .reference/lxserver-2.2.0 or set LXSERVER_SOURCE')
const names = ['wy', 'tx', 'kg', 'kw', 'mg']
const imports = names.flatMap(source => [
  `import ${source}Search from '${sdk.replaceAll('\\', '/')}/${source}/musicSearch.js';`,
  `import ${source}Lists from '${sdk.replaceAll('\\', '/')}/${source}/songList.js';`,
  `import ${source}Boards from '${sdk.replaceAll('\\', '/')}/${source}/leaderboard.js';`,
  `import ${source}Lyric from '${sdk.replaceAll('\\', '/')}/${source}/lyric.js';`,
])
const bundle = await build({
  absWorkingDir: root,
  stdin: {
    resolveDir: root,
    contents: imports.join('\n') + `
      import { makeCatalog } from './shared/catalog-api.js';
      import { makeChartCoverLoader } from './shared/chart-cover.js';
      import { httpFetch } from './shared/catalog-http.js';
      import { weapi } from '${sdk.replaceAll('\\', '/')}/wy/utils/crypto.js';
      globalThis.SoundShelfCatalog = makeCatalog({
        ${names.map(source => `${source}: [${source}Search,${source}Lists,${source}Boards,${source}Lyric]`).join(',')}
      }, makeChartCoverLoader({ httpFetch, weapi }));
    `,
  },
  outfile: 'shared/catalog.bundle.js',
  bundle: true,
  metafile: true,
  target: 'chrome100',
  inject: ['./shared/node-globals.js'],
  legalComments: 'inline',
  banner: { js: '/* Catalog adapters derived from XCQ0607/lxserver v2.2.0, commit 13865b8, Apache-2.0. See THIRD_PARTY.md. */' },
  alias: { crypto: 'crypto-browserify', stream: 'stream-browserify', events: 'events/' },
  plugins: [{
    name: 'portable-catalog',
    setup(plugin) {
      plugin.onResolve({ filter: /^node:crypto$/ }, () => ({ path: path.join(root, 'node_modules/crypto-browserify/index.js') }))
      plugin.onResolve({ filter: /^(?:\.\.\/){2,3}request$/ }, () => ({ path: path.join(root, 'shared/catalog-http.js') }))
      plugin.onResolve({ filter: /^(?:\.\.\/){2,3}index$|^@renderer\/utils$/ }, () => ({ path: path.join(root, 'shared/catalog-format.js') }))
      plugin.onResolve({ filter: /^@renderer\/utils\/musicSdk\/kg\/vendors\/infSign.min$/ }, () => ({ path: path.join(sdk, 'kg/vendors/infSign.min.js') }))
      plugin.onResolve({ filter: /^@\/utils\/lyricUtils\/kg$/ }, () => ({ path: path.join(sources, 'src/utils/lyricUtils/kg.js') }))
      plugin.onResolve({ filter: /^zlib$/ }, () => ({ path: path.join(root, 'shared/catalog-zlib.js') }))
      plugin.onResolve({ filter: /^dns$/ }, args => ({ path: args.path, namespace: 'unused-node-module' }))
      plugin.onLoad({ filter: /.*/, namespace: 'unused-node-module' }, () => ({
        contents: 'const unavailable = () => { throw Error("This optional Node feature is unavailable") }; export { unavailable as inflate }; export default { lookup: unavailable }',
      }))
    },
  }],
})
const notices = [
  'SoundShelf music catalog uses source from XCQ0607/lxserver v2.2.0, commit 13865b8.',
  'Copyright (c) 2026 XCQ0607 and contributors.',
  'Local changes include HTTP transport, data formatting, Node compatibility, and catalog dispatch.',
  'Upstream notices: https://github.com/XCQ0607/lxserver/blob/v2.2.0/README.md#-开源协议',
  readFileSync(path.join(sources, 'LICENSE'), 'utf8'),
]
const packages = new Set()
for (const name of Object.keys(bundle.metafile.inputs)) {
  const parts = name.split('/')
  const index = parts.lastIndexOf('node_modules')
  if (index < 0) continue
  packages.add(parts.slice(0, index + (parts[index + 1].startsWith('@') ? 3 : 2)).join('/'))
}
for (const directory of [...packages].sort()) {
  for (const entry of readdirSync(path.join(root, directory), { withFileTypes: true })) {
    if (entry.isFile() && /^(licen[cs]e|copying|notice)(\.|$)/i.test(entry.name)) {
      notices.push(directory + '/' + entry.name, readFileSync(path.join(root, directory, entry.name), 'utf8'))
    }
  }
}
writeFileSync(path.join(root, 'shared/catalog-licenses.txt'), notices.join('\n\n'))
