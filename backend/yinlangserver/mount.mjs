import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const root = process.argv[2] || '/server'
const { version } = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'))
if (!/^v?2\.2\.0$/.test(version)) throw Error(`Unsupported LXServer version: ${version}`)

function replaceOnce(source, before, after) {
  const offset = source.indexOf(before)
  if (offset === -1 || source.indexOf(before, offset + before.length) !== -1) {
    throw Error(`Unexpected LXServer source: ${before}`)
  }
  return source.slice(0, offset) + after + source.slice(offset + before.length)
}

if (process.argv.includes('--source')) {
  const queueFile = path.join(root, 'src/server/task/serverDownloadQueue.ts')
  let queue = await readFile(queueFile, 'utf8')
  queue = replaceOnce(queue, 'export interface ServerDownloadTask {', `interface PushedMedia {
  url: string
  headers: Record<string, string>
  kind: 'music' | 'audiobook'
  mediaType: string
}

export interface ServerDownloadTask {
  pushedMedia?: PushedMedia`)
  queue = replaceOnce(queue, 'interface ResolveResult {', `interface ResolveResult {
  openMedia?: fileCache.DownloadProvenance['openMedia']`)
  queue = replaceOnce(queue, "}, null, 2), 'utf8')", "}, null, 2), { encoding: 'utf8', mode: 0o600 })")
  queue = replaceOnce(queue, 'songInfo: raw.songInfo,', 'songInfo: raw.songInfo,\n        pushedMedia: raw.pushedMedia,')
  queue = replaceOnce(queue, '    const resolved = await resolver(task)', `    let resolved: ResolveResult
    if (task.pushedMedia) {
      const modulePath = '../../../yinlang/push.mjs'
      const { resolvePushedMedia } = await import(modulePath)
      resolved = resolvePushedMedia(task.pushedMedia, task.requestedQuality)
    } else {
      resolved = await resolver(task)
    }`)
  queue = replaceOnce(queue, '      sourceName: resolved.sourceName,', '      sourceName: resolved.sourceName,\n      openMedia: resolved.openMedia,')
  queue = replaceOnce(queue, "    task.errorMsg = ''\n  } catch", "    task.errorMsg = ''\n    delete task.pushedMedia\n  } catch")
  queue = replaceOnce(queue, 'export const enqueue = (username: string, inputs: QueueInput[]) => {',
    'export const enqueue = (username: string, inputs: QueueInput[], pushedMedia?: PushedMedia) => {')
  queue = replaceOnce(queue, '    const id = sanitizeId(input.id)', `    const id = sanitizeId(input.id)
    // Only the authenticated phone endpoint can submit or replace a push task.
    if (id.startsWith('yinlang_') !== Boolean(pushedMedia)) continue`)
  queue = replaceOnce(queue, '      id, username,', '      id, username, pushedMedia,')
  queue = replaceOnce(queue,
    'export const remove = (username: string, options: { id?: string; all?: boolean; completed?: boolean }) => {',
    'export const remove = (username: string, options: { id?: string; all?: boolean; completed?: boolean; history?: boolean }) => {')
  queue = replaceOnce(queue,
    "    const shouldRemove = options.all || (options.id && task.id === options.id) || (options.completed && ['finished', 'exists'].includes(task.status))",
    "    const historyStatus = ['finished', 'exists', 'error'].includes(task.status) && !controllers.has(key)\n    const shouldRemove = options.all || (options.id && task.id === options.id && (!options.history || historyStatus)) || (options.completed && ['finished', 'exists'].includes(task.status))")
  await writeFile(queueFile, queue)

  const cacheFile = path.join(root, 'src/server/music/fileCache.ts')
  let cache = await readFile(cacheFile, 'utf8')
  cache = replaceOnce(cache, '    customTargetDir?: string\n}', `    customTargetDir?: string
    openMedia?: (signal?: AbortSignal) => Promise<import('node:stream').Readable & {
        statusCode?: number
        headers: http.IncomingHttpHeaders
    }>
}`)
  cache = replaceOnce(cache, 'provenance.requestedSource, provenance.downloadSource, provenance.sourceName])',
    'provenance.requestedSource, provenance.downloadSource, provenance.sourceName, Boolean(provenance.openMedia)])')
  cache = replaceOnce(cache, '        let req: http.ClientRequest', `        let req: http.ClientRequest
        let pushedStream: import('node:stream').Readable | undefined`)
  cache = replaceOnce(cache, '            if (req) req.destroy()',
    '            if (req) req.destroy()\n            pushedStream?.destroy()')
  cache = replaceOnce(cache, '            req = protocol.get(currentUrl, (res) => {',
    "            const receive = (res: Awaited<ReturnType<NonNullable<DownloadProvenance['openMedia']>>>) => {")
  cache = replaceOnce(cache, "            fileStream.on('error', (err) => { safeUnlink(fileStream, tempPath); fail(err) })\n        })\n        req.on('error',", `            fileStream.on('error', (err) => {
                pushedStream?.destroy()
                safeUnlink(fileStream, tempPath)
                fail(err)
            })
        }
        if (provenance.openMedia) {
            void provenance.openMedia(signal).then(stream => {
                pushedStream = stream
                if (settled) { stream.destroy(); return }
                stream.on('error', err => { safeUnlink(fileStream, tempPath); fail(err) })
                receive(stream)
            }).catch(err => { pushedStream?.destroy(); safeUnlink(fileStream, tempPath); fail(err) })
            return
        }
        req = protocol.get(currentUrl, receive)
        req.on('error',`)
  await writeFile(cacheFile, cache)

  const playerFile = path.join(root, 'public/music/js/download/download_manager.js')
  let player = await readFile(playerFile, 'utf8')
  player = replaceOnce(player, '            const updatedTasks = [];', '            const updatedTasks = [];\n            let queueChanged = false;')
  player = replaceOnce(player, '                if (!task) {\n                    task = {', '                if (!task) {\n                    queueChanged = true;\n                    task = {')
  player = replaceOnce(player, '                this.tasks = this.tasks.filter(task => !task.serverManaged || remoteIds.has(task.serverQueueId || task.id));', `                const retained = this.tasks.filter(task => !task.serverManaged || remoteIds.has(task.serverQueueId || task.id));
                queueChanged ||= retained.length !== this.tasks.length;
                this.tasks = retained;`)
  player = replaceOnce(player, '            if (render) this.renderList();', '            if (render || queueChanged) this.renderList();')
  player = replaceOnce(player, '        if (!activeTasks.length) return;\n        if (activeTasks.some(t => t.serverManaged)) await this.syncServerQueue(false);', `        const drawerOpen = this.drawer && !this.drawer.classList.contains('translate-x-full');
        if ((drawerOpen && !document.hidden) || activeTasks.some(t => t.serverManaged)) await this.syncServerQueue(false);
        if (!activeTasks.length) return;`)
  await writeFile(playerFile, player)
  process.exit(0)
}

const filename = path.join(root, 'server/server/server.js')
const source = await readFile(filename, 'utf8')
const handler = /^([ \t]*)const httpServer = [\w$]+(?:\.default)?\.createServer\(async \(req, res\) => \{\r?\n[ \t]*try \{/gm
const matches = [...source.matchAll(handler)]
if (matches.length !== 1 || source.includes('yinlang/push.mjs')) {
  throw Error('LXServer v2.2.0 request handler did not match the expected build')
}

const [original, indent] = matches[0]
const hook = [
  "if (/^\\/(?:health|v1\\/jobs)(?:\\/|\\?|$)/.test(req.url || '')) {",
  "    const { handlePushRequest } = await import('../../yinlang/push.mjs');",
  "    if (await handlePushRequest(req, res, global.lx?.restoreMaintenance, require('./task/serverDownloadQueue'))) return;",
  "}",
].map(line => indent + '        ' + line).join('\n')

await writeFile(filename, source.replace(handler, () => original + '\n' + hook))
