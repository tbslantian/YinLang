import http from 'node:http'
import https from 'node:https'
import { lookup } from 'node:dns/promises'
import { BlockList } from 'node:net'
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto'
import { mkdir, mkdtemp, readdir, readFile, rename, rm, stat, writeFile, access } from 'node:fs/promises'
import { constants, createReadStream, createWriteStream } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
const embedded = process.env.YINLANG_LXSERVER === '1'
if (!embedded) {
  await import('./catalog.bundle.js').catch(error => {
    if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error
    return import('../shared/catalog.bundle.js')
  })
}

const token = (process.env.DOWNLOAD_TOKEN || '').trim()
const pushEnabled = token.length >= 32
const lxQueueUser = process.env.YINLANG_QUEUE_USER?.trim() || '_open'
if (!pushEnabled && !embedded) throw Error('DOWNLOAD_TOKEN must have at least 32 characters')
const port = Number(process.env.PORT || 8787)
if (!embedded && (!Number.isInteger(port) || port < 1 || port > 65535)) throw Error('Invalid PORT')
const maxBytes = Number(process.env.MAX_DOWNLOAD_BYTES || 2147483648)
if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) throw Error('Invalid MAX_DOWNLOAD_BYTES')
const concurrency = Number(process.env.DOWNLOAD_CONCURRENCY || 2)
if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 8) throw Error('DOWNLOAD_CONCURRENCY must be between 1 and 8')
const state = path.resolve(process.env.STATE_DIR || '/state')
const roots = {
  music: path.resolve(process.env.MUSIC_DIR || '/downloads/music'),
  audiobook: path.resolve(process.env.AUDIOBOOK_DIR || '/downloads/audiobooks'),
}
if (roots.music === roots.audiobook) throw Error('Music and audiobooks must use different directories')
const allowedPrivate = new Set((process.env.PRIVATE_MEDIA_HOSTS || '').toLowerCase().split(',').map(v => v.trim()).filter(Boolean))
const blocked = new BlockList()
for (const [address, bits] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
  ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24],
  ['192.168.0.0', 16], ['198.18.0.0', 15], ['198.51.100.0', 24],
  ['203.0.113.0', 24], ['224.0.0.0', 3],
]) blocked.addSubnet(address, bits, 'ipv4')
const global6 = new BlockList()
global6.addSubnet('2000::', 3, 'ipv6')
for (const [address, bits] of [['2001::', 23], ['2001:db8::', 32], ['2002::', 16]]) blocked.addSubnet(address, bits, 'ipv6')

const types = new Map(Object.entries({
  'audio/mpeg': 'mp3', 'audio/mp3': 'mp3', 'audio/flac': 'flac', 'audio/x-flac': 'flac',
  'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/mp4a-latm': 'm4a', 'audio/aac': 'aac', 'audio/aacp': 'aac',
  'audio/ogg': 'ogg', 'application/ogg': 'ogg', 'audio/opus': 'opus', 'audio/wav': 'wav',
  'audio/x-wav': 'wav', 'audio/vnd.wave': 'wav', 'audio/x-ms-wma': 'wma',
  'audio/x-m4b': 'm4b', 'audio/webm': 'webm', 'audio/aiff': 'aiff', 'audio/amr': 'amr',
  'audio/ac3': 'ac3', 'audio/eac3': 'eac3', 'audio/x-eac3': 'eac3', 'audio/x-matroska': 'mka',
}))
const extensions = new Set(types.values())
const musicQualities = new Set(['128k', '192k', '320k', 'flac', 'flac24bit', 'hires', 'atmos', 'atmos_plus', 'master'])
const videoTypes = new Map([
  ['video/mp4', 'mp4'], ['video/iso.segment', 'mp4'], ['video/webm', 'webm'], ['video/x-flv', 'flv'],
])
const videoInputs = { mp4: 'mov', webm: 'matroska', flv: 'flv' }
const jobs = new Map()
const running = new Map()
let submitting = 0
let stopping = false
const runFfmpeg = promisify(execFile)
const musicProviders = ['wy', 'tx', 'kg', 'kw', 'mg']
const sourcesFile = path.join(state, 'music-sources.json')
let musicSources = []
let writeSources = Promise.resolve()

if (!embedded) {
  globalThis.catalogTransport = async (address, options, signal) => {
    const deadline = AbortSignal.any([signal, AbortSignal.timeout(12_000)])
    const { response, url } = await openExternal(mediaUrl(address), options, deadline)
    return { status: response.statusCode, body: await responseText(response, 4_000_000), url: url.href, headers: response.headers }
  }
}

function mediaUrl(input) {
  if (typeof input !== 'string' || input.length > 8192) throw Error('Invalid media URL')
  const url = new URL(input)
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) throw Error('Use an HTTP(S) media URL')
  return url
}

async function openExternal(url, options, signal, hops = 0) {
  if (hops > 5) throw Error('Too many redirects')
  const host = url.hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase()
  if (!allowedPrivate.has(host) && url.port) throw Error('Custom media ports require PRIVATE_MEDIA_HOSTS')
  const addresses = await lookup(host, { all: true })
  const address = addresses.find(entry => {
    if (allowedPrivate.has(host)) return true
    if (entry.family === 4) return !blocked.check(entry.address, 'ipv4')
    return global6.check(entry.address, 'ipv6') && !blocked.check(entry.address, 'ipv6')
  })
  if (!address) throw Error('Private media host is not allowed')
  const transport = url.protocol === 'https:' ? https : http
  const response = await new Promise((resolve, reject) => {
    const request = transport.request(url, {
      agent: false,
      signal,
      method: options.method || 'GET',
      headers: options.body == null ? options.headers : { ...options.headers, 'content-length': Buffer.byteLength(options.body) },
      lookup: (_name, options, callback) => options.all ? callback(null, [address]) : callback(null, address.address, address.family),
    }, resolve)
    request.on('error', reject)
    request.end(options.body)
  })
  if (options.follow !== false && [301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location) {
    response.destroy()
    const next = mediaUrl(new URL(response.headers.location, url).href)
    if (response.statusCode === 303 || ([301, 302].includes(response.statusCode) && options.method === 'POST')) {
      options = { ...options, method: 'GET', body: undefined }
    }
    return openExternal(next, { ...options, headers: next.origin === url.origin ? options.headers : {} }, signal, hops + 1)
  }
  return { response, url }
}

async function responseText(response, maximum) {
  const chunks = []
  let size = 0
  for await (const chunk of response) {
    size += chunk.length
    if (size > maximum) throw Error('Response is too large')
    chunks.push(chunk)
  }
  return Buffer.concat(chunks).toString('utf8')
}

function cleanName(value, fallback) {
  const text = typeof value === 'string' ? value.normalize('NFC').replace(/[<>:"/\\|?*\u0000-\u001f\u007f]/g, '_').replace(/^[. ]+|[. ]+$/g, '') : ''
  const name = [...text].slice(0, 52).join('')
  return name && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name) ? name : fallback
}

function validHeaders(value) {
  const result = {}
  for (const [name, entry] of Object.entries(value || {})) {
    const key = name.toLowerCase()
    if (['referer', 'user-agent', 'origin', 'authorization', 'cookie'].includes(key)
      && typeof entry === 'string' && /^[\x20-\x7e]{1,4096}$/.test(entry)) result[key] = entry
  }
  return result
}

async function persist(job) {
  const file = path.join(state, job.id + '.json')
  const temp = file + '.tmp'
  await writeFile(temp, JSON.stringify(job), { mode: 0o600 })
  await rename(temp, file)
}

function limitStream(job) {
  return new Transform({
    transform(chunk, _encoding, done) {
      job.bytes += chunk.length
      done(job.bytes > maxBytes ? Error('Download exceeds configured limit') : null, chunk)
    },
  })
}

function headersFor(media, url) {
  const { authorization, cookie, ...publicHeaders } = media.headers
  return url.origin === new URL(media.url).origin ? media.headers : publicHeaders
}

async function fetchHls(job, media, source, directory, signal) {
  let current = source
  let lines
  for (let level = 0; level < 4; level++) {
    if (current.response.statusCode !== 200) throw Error('HLS source returned HTTP ' + current.response.statusCode)
    lines = (await responseText(current.stream || current.response, 1_000_000)).split(/\r?\n/).map(line => line.trim()).filter(Boolean)
    if (lines[0] !== '#EXTM3U') throw Error('Invalid HLS playlist')
    const audio = lines.find(line => line.startsWith('#EXT-X-MEDIA:') && /[:,]TYPE=AUDIO(?:,|$)/.test(line) && /\bURI="/.test(line))
    const variant = lines.findIndex(line => line.startsWith('#EXT-X-STREAM-INF:'))
    if (!audio && variant < 0) break
    const reference = audio ? /\bURI="([^"]+)"/.exec(audio)[1] : lines.slice(variant + 1).find(line => !line.startsWith('#'))
    if (!reference || level === 3) throw Error('HLS playlist nesting is too deep')
    const url = mediaUrl(new URL(reference, current.url).href)
    current = await openExternal(url, { headers: headersFor(media, url) }, signal)
  }
  if (!lines.includes('#EXT-X-ENDLIST')) throw Error('Live HLS streams cannot be downloaded as chapters')
  if (lines.some(line => line.startsWith('#EXT-X-BYTERANGE:') || /(?:^|,)BYTERANGE=/.test(line))) {
    throw Error('HLS playlists with byte ranges are not supported')
  }
  if (lines.length > 20_000) throw Error('HLS playlist has too many segments')
  const assets = new Map()
  async function local(reference, type) {
    const url = mediaUrl(new URL(reference, current.url).href)
    if (assets.has(url.href)) return assets.get(url.href)
    const match = /\.([a-z0-9]{1,6})$/i.exec(url.pathname)
    const suffix = type || (match ? match[1] : 'ts')
    const file = 'asset-' + assets.size + '.' + suffix
    const { response } = await openExternal(url, { headers: headersFor(media, url) }, signal)
    if (response.statusCode !== 200) { response.destroy(); throw Error('HLS segment HTTP ' + response.statusCode) }
    await pipeline(response, limitStream(job), createWriteStream(path.join(directory, file), { flags: 'wx', mode: 0o600 }))
    assets.set(url.href, file)
    return file
  }
  const rewritten = []
  for (const line of lines) {
    if (line.startsWith('#EXT-X-KEY:')) {
      if (line.includes('METHOD=NONE')) { rewritten.push('#EXT-X-KEY:METHOD=NONE'); continue }
      if (!line.includes('METHOD=AES-128') || /\bKEYFORMAT="(?!identity")/.test(line)) throw Error('Unsupported HLS encryption')
      const match = /\bURI="([^"]+)"/.exec(line)
      if (!match) throw Error('Invalid HLS key URL')
      rewritten.push(line.replace(match[0], 'URI="' + await local(match[1], 'key') + '"'))
    } else if (line.startsWith('#EXT-X-MAP:')) {
      const match = /\bURI="([^"]+)"/.exec(line)
      if (!match) throw Error('Invalid HLS initialization URL')
      rewritten.push(line.replace(match[0], 'URI="' + await local(match[1], 'mp4') + '"'))
    } else if (!line.startsWith('#')) rewritten.push(await local(line))
    else if (/^#(?:EXTM3U|EXTINF|EXT-X-(?:VERSION|TARGETDURATION|MEDIA-SEQUENCE|PLAYLIST-TYPE|DISCONTINUITY|ENDLIST|PROGRAM-DATE-TIME))(?:$|:)/.test(line)) {
      rewritten.push(line)
    }
  }
  const playlist = path.join(directory, 'audio.m3u8')
  await writeFile(playlist, rewritten.join('\n') + '\n', { mode: 0o600 })
  const audio = path.join(directory, job.kind === 'music' && job.quality ? 'audio.mka' : 'audio.m4a')
  const codec = job.kind === 'music' && job.quality ? ['-c:a', 'copy'] : ['-c:a', 'aac', '-b:a', '160k']
  try {
    await runFfmpeg('ffmpeg', ['-nostdin', '-v', 'error', '-protocol_whitelist', 'file,crypto,pipe',
      '-allowed_extensions', 'ALL', '-i', playlist, '-map', '0:a:0', ...codec, audio],
    { timeout: 300_000, maxBuffer: 32_768, signal })
  } catch { throw Error('Could not assemble HLS audio') }
  return audio
}

async function download(job, controller) {
  let temp
  let response
  let workspace
  try {
    if (job.file) {
      const old = path.join(roots[job.kind], job.file)
      try {
        await access(old)
        job.status = 'completed'
        return
      } catch { await rm(old + '.part', { force: true }) }
    }
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30 * 60_000)])
    job.file = undefined
    job.bytes = 0
    job.total = 0
    const { media, source: initial } = job.song
      ? await resolveMusic(job.song.source, job.song, job.quality, signal)
      : { media: job, source: await openExternal(mediaUrl(job.url), { headers: job.headers }, signal) }
    response = initial.response
    signal.throwIfAborted()
    if (response.statusCode !== 200) throw Error('Media server HTTP ' + response.statusCode)
    const source = job.kind === 'music' && !initial.audio ? await inspectMusicResponse(initial, media, job.quality) : initial
    const mime = (response.headers['content-type'] || '').split(';')[0].trim().toLowerCase()
    const hinted = path.extname(source.url.pathname).slice(1).toLowerCase()
    const isAudio = source.audio?.extension || mime.startsWith('audio/') || types.has(mime)
    const binary = ['', 'application/octet-stream', 'binary/octet-stream', 'application/download'].includes(mime)
    const hls = source.audio?.hls ?? (media.mediaType === 'hls' || hinted === 'm3u8' || mime.includes('mpegurl'))
    const video = job.kind === 'audiobook' && (videoTypes.get(mime) || (binary && Object.hasOwn(videoInputs, hinted) ? hinted : null))
    if (!hls && !video && !isAudio && !binary) throw Error('Source did not return audio')
    const extension = hls && job.kind === 'music' && job.quality ? 'mka'
      : hls || video ? 'm4a' : source.audio?.extension || types.get(mime) || (extensions.has(hinted) ? hinted : null)
    if (!extension) throw Error('Unrecognized audio format')
    job.total = hls ? 0 : Number(response.headers['content-length']) || 0
    if (job.total > maxBytes) throw Error('Download exceeds configured limit')
    const folder = job.kind === 'audiobook' ? job.collection : ''
    job.file = path.join(folder, job.title + ' [' + job.id.slice(0, 8) + '].' + extension)
    await persist(job)
    const dest = path.join(roots[job.kind], job.file)
    await mkdir(path.dirname(dest), { recursive: true })
    job.bytes = 0
    workspace = path.join(roots[job.kind], '.soundshelf-work', job.id)
    await rm(workspace, { recursive: true, force: true })
    await mkdir(workspace, { recursive: true, mode: 0o700 })
    if (hls) temp = await fetchHls(job, media, source, workspace, signal)
    else {
      temp = path.join(workspace, 'audio.' + (video || extension))
      await pipeline(source.stream || response, limitStream(job), createWriteStream(temp, { flags: 'w', mode: 0o644 }))
      if (video) {
        const output = path.join(workspace, 'audio.m4a')
        try {
          await runFfmpeg('ffmpeg', ['-nostdin', '-v', 'error', '-protocol_whitelist', 'file,pipe', '-f', videoInputs[video], '-i', temp,
            '-map', '0:a:0', '-c:a', 'aac', '-b:a', '160k', output], { timeout: 300_000, maxBuffer: 32_768, signal })
        } catch { throw Error('Could not extract audio from media file') }
        temp = output
      }
    }
    if (!job.bytes) throw Error('Empty audio response')
    if (job.kind === 'music' && (job.artist || job.album || job.trackTitle)) {
      const tagged = path.join(workspace, 'tagged.' + extension)
      const metadata = Object.entries({ title: job.trackTitle, artist: job.artist, album: job.album })
        .filter(([, value]) => value).flatMap(([key, value]) => ['-metadata', key + '=' + value])
      try {
        await runFfmpeg('ffmpeg', ['-nostdin', '-v', 'error', '-y', '-protocol_whitelist', 'file,pipe', '-i', temp,
          '-map', '0', '-c', 'copy', ...metadata, tagged], { timeout: 60_000, maxBuffer: 32_768, signal })
        temp = tagged
      } catch { /* Retain the original file when its container cannot be tagged. */ }
    }
    signal.throwIfAborted()
    await rename(temp, dest)
    job.status = 'completed'
  } catch (error) {
    job.status = controller.signal.aborted ? 'queued' : 'failed'
    job.error = job.status === 'queued' ? undefined : error.code || (error instanceof TypeError ? 'Invalid media response' : error.message)
    if (temp) await rm(temp, { force: true }).catch(() => {})
  } finally {
    response?.destroy()
    if (workspace) await rm(workspace, { recursive: true, force: true }).catch(() => {})
    job.updatedAt = new Date().toISOString()
    if (job.status === 'completed') {
      delete job.url
      delete job.headers
      delete job.song
    }
    await persist(job)
  }
}

function schedule() {
  if (stopping) return
  for (const job of jobs.values()) {
    if (running.size >= concurrency) break
    if (job.status !== 'queued' || running.has(job.id)) continue
    job.status = 'downloading'
    const controller = new AbortController()
    running.set(job.id, controller)
    download(job, controller).catch(error => console.error('Cannot save job', job.id, error.code)).finally(() => {
      running.delete(job.id)
      schedule()
    })
  }
}

function publicJob({ url, headers, song, ...job }) {
  return { ...job, retryable: job.status === 'failed' && Boolean(url || song) }
}

function send(res, status, payload) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(payload))
}

function authorized(req) {
  const candidate = Buffer.from(req.headers.authorization || '')
  const expected = Buffer.from('Bearer ' + token)
  return candidate.length === expected.length && timingSafeEqual(candidate, expected)
}

function sendError(res, error) {
  send(res, error.status || (error.code ? 500 : 400), { error: error.code ? '请求失败：' + error.code : error.message })
}

function nativeJob(task) {
  const { songInfo: song } = task
  const status = {
    waiting: 'queued', downloading: 'downloading', tagging: 'downloading', paused: 'paused',
    finished: 'completed', exists: 'completed', error: 'failed',
  }[task.status]
  return {
    id: task.id, kind: song.source === 'audiobook' ? 'audiobook' : 'music',
    title: song.singer ? `${song.singer} - ${song.name}` : song.name,
    quality: task.quality, status, bytes: task.received, total: task.total,
    error: task.errorMsg, createdAt: new Date(task.createdAt).toISOString(),
    updatedAt: new Date(task.updatedAt).toISOString(),
  }
}

function enqueueNative(queue, body) {
  if (!body || !['music', 'audiobook'].includes(body.kind)) throw Error('Invalid download category')
  const url = mediaUrl(body.url).href
  const quality = musicQualities.has(body.quality) ? body.quality : 'unknown'
  const title = cleanName(body.title, 'audio')
  const id = 'yinlang_' + randomUUID()
  const isBook = body.kind === 'audiobook'
  const source = isBook ? 'audiobook' : musicProviders.includes(body.source) ? body.source : 'unknown'
  const artist = isBook ? cleanName(body.collection, '未分类有声书') : cleanName(body.artist, '')
  const name = isBook ? title : cleanName(body.trackTitle, title)
  const identity = createHash('sha256').update(JSON.stringify([body.kind, artist, title, body.album])).digest('hex').slice(0, 24)
  const songmid = !isBook && source !== 'unknown' && typeof body.songId === 'string'
    && /^[a-zA-Z0-9_-]{1,120}$/.test(body.songId) ? body.songId : `${source}_${identity}`
  const media = { url, headers: validHeaders(body.headers), kind: body.kind, mediaType: body.mediaType === 'hls' ? 'hls' : '' }
  const [task] = queue.enqueue(lxQueueUser, [{
    id, songInfo: { songmid, source, name, singer: artist, albumName: cleanName(body.album, '') },
    quality, enableOnlyDownloadMode: true, cacheLyric: false, embedLyric: false,
  }], media)
  return nativeJob(task)
}

export async function handlePushRequest(req, res, maintenance = false, nativeQueue) {
  const pathname = req.url?.split('?', 1)[0]
  const health = pathname === '/health'
  const jobsPath = pathname === '/v1/jobs'
  const jobHistory = pathname === '/v1/jobs/history'
  if (!health && !jobsPath && !jobHistory) return false
  if (!pushEnabled) {
    send(res, 503, { error: '服务器推送未启用。请设置至少 32 字符的 DOWNLOAD_TOKEN。' })
    return true
  }
  if (!authorized(req)) {
    send(res, 401, { error: 'Invalid token' })
    return true
  }
  try {
    if (nativeQueue) {
      const config = globalThis.lx?.config
      if (lxQueueUser !== '_open' && !config?.users?.some(user => user.name === lxQueueUser)) {
        send(res, 503, { error: 'YINLANG_QUEUE_USER 未对应有效的 LXServer 本地账号' })
        return true
      }
    }
    if (health && req.method === 'GET') {
      const ready = !stopping && !maintenance
      send(res, ready ? 200 : 503, { status: ready ? 'ok' : 'unavailable' })
    } else if (jobsPath && req.method === 'GET') {
      const ordered = nativeQueue
        ? nativeQueue.list(lxQueueUser).filter(task => task.id.startsWith('yinlang_')).slice(-100).reverse().map(nativeJob)
        : [...jobs.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 100).map(publicJob)
      send(res, 200, { jobs: ordered })
    } else if (jobsPath && req.method === 'POST') {
      if (maintenance) send(res, 503, { error: 'LXServer 正在恢复维护，请稍后重试' })
      else {
        const body = await readBody(req)
        send(res, 202, nativeQueue ? enqueueNative(nativeQueue, body) : await enqueue(body))
      }
    } else if (jobHistory && req.method === 'DELETE') {
      let removed = 0
      if (nativeQueue) {
        const terminal = new Set(['finished', 'exists', 'error'])
        const candidates = nativeQueue.list(lxQueueUser)
          .filter(task => task.id.startsWith('yinlang_') && terminal.has(task.status))
          .map(task => task.id)
        for (const id of candidates) nativeQueue.remove(lxQueueUser, { id, history: true })
        const remaining = new Set(nativeQueue.list(lxQueueUser).map(task => task.id))
        removed = candidates.filter(id => !remaining.has(id)).length
      } else {
        const candidates = [...jobs.values()].filter(job => ['completed', 'failed'].includes(job.status)
          && !running.has(job.id) && !retrying.has(job.id))
        for (const job of candidates) jobs.delete(job.id)
        await Promise.all(candidates.map(job => rm(path.join(state, job.id + '.json'), { force: true })))
        removed = candidates.length
      }
      send(res, 200, { status: 'cleared', removed })
    } else {
      res.setHeader('allow', health ? 'GET' : jobHistory ? 'DELETE' : 'GET, POST')
      send(res, 405, { error: 'Method not allowed' })
    }
  } catch (error) {
    sendError(res, error)
  }
  return true
}

export function resolvePushedMedia(media, quality) {
  return {
    url: media.url,
    openMedia: async queueSignal => {
      const deadline = AbortSignal.timeout(30 * 60_000)
      const signal = queueSignal ? AbortSignal.any([queueSignal, deadline]) : deadline
      const initial = await openExternal(mediaUrl(media.url), { headers: media.headers }, signal)
      const { response } = initial
      let workspace
      try {
        if (response.statusCode !== 200) throw Error('音源返回 HTTP ' + response.statusCode)
        if (Number(response.headers['content-length']) > maxBytes) throw Error('音频超过下载大小上限')
        const source = media.kind === 'music' ? await inspectMusicResponse(initial, media, quality) : initial
        const mime = String(response.headers['content-type'] || '').split(';')[0].trim().toLowerCase()
        const suffix = path.extname(source.url.pathname).slice(1).toLowerCase()
        const hls = source.audio?.hls ?? (media.mediaType === 'hls' || suffix === 'm3u8' || mime.includes('mpegurl'))
        const video = media.kind === 'audiobook' && (videoTypes.get(mime)
          || (['', 'application/octet-stream', 'binary/octet-stream'].includes(mime) && Object.hasOwn(videoInputs, suffix) && suffix))
        if (!hls && !video && media.kind === 'audiobook' && !mime.startsWith('audio/')
          && !types.has(mime) && !extensions.has(suffix)) throw Error('音源没有返回有效的音频文件')
        let stream = source.stream || response
        let headers = response.headers
        if (hls || video) {
          workspace = await mkdtemp(path.join(tmpdir(), 'yinlang-media-'))
          let audio
          if (hls) {
            audio = await fetchHls({ kind: media.kind, quality: quality === 'unknown' ? '' : quality, bytes: 0 }, media, source, workspace, signal)
          } else {
            const input = path.join(workspace, 'input.' + video)
            audio = path.join(workspace, 'audio.m4a')
            await pipeline(stream, limitStream({ bytes: 0 }), createWriteStream(input), { signal })
            await runFfmpeg('ffmpeg', ['-nostdin', '-v', 'error', '-protocol_whitelist', 'file,pipe', '-f', videoInputs[video],
              '-i', input, '-map', '0:a:0', '-c:a', 'aac', '-b:a', '160k', audio], { timeout: 300_000, maxBuffer: 32_768, signal })
          }
          const { size } = await stat(audio)
          headers = { 'content-type': audio.endsWith('.m4a') ? 'audio/mp4' : 'audio/x-matroska', 'content-length': String(size) }
          stream = createReadStream(audio, { signal })
        }
        const output = Readable.from((async function* () {
          let bytes = 0
          for await (const chunk of stream) {
            signal.throwIfAborted()
            bytes += chunk.length
            if (bytes > maxBytes) throw Error('音频超过下载大小上限')
            yield chunk
          }
          if (!bytes) throw Error('音源返回了空文件')
        })(), { objectMode: false })
        output.once('close', () => {
          stream.destroy()
          response.destroy()
          if (workspace) void rm(workspace, { recursive: true, force: true }).catch(() => {})
        })
        return Object.assign(output, { statusCode: 200, headers })
      } catch (error) {
        response.destroy()
        if (workspace) await rm(workspace, { recursive: true, force: true }).catch(() => {})
        // Keep signed URLs and media credentials out of the public queue's error message.
        const message = error.message
        if (/^(音源|音频|文件实际|无法确认所选音质)/.test(message)) throw Error(message)
        throw Error(queueSignal?.aborted ? 'Aborted' : '无法下载推送的音频，请检查音源或从手机重新推送')
      }
    },
  }
}

async function readBody(req) {
  const chunks = []
  let length = 0
  for await (const chunk of req.iterator({ destroyOnReturn: false })) {
    length += chunk.length
    if (length > 16_384) throw Error('Request is too large')
    chunks.push(chunk)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function persistSources(change) {
  const task = writeSources.then(async () => {
    const updated = change(musicSources)
    const temp = sourcesFile + '.tmp'
    await writeFile(temp, JSON.stringify(updated), { mode: 0o600 })
    await rename(temp, sourcesFile)
    musicSources = updated
  })
  writeSources = task.catch(() => {})
  return task
}

async function resolveMusic(platform, song, quality, signal) {
  const candidates = musicSources.filter(source => source.platform === platform)
  if (!candidates.length) throw Error('请先添加支持此平台的音乐解析源')
  let lastError
  for (const source of candidates) {
    signal.throwIfAborted()
    try {
      const endpoint = mediaUrl(source.endpoint)
      const body = JSON.stringify({ source: platform, quality, musicInfo: song })
      const { response } = await openExternal(endpoint, {
        method: 'POST', body, headers: { 'content-type': 'application/json' }, follow: false,
      }, AbortSignal.any([signal, AbortSignal.timeout(15_000)]))
      if (response.statusCode !== 200) { response.destroy(); throw Error('解析源 HTTP ' + response.statusCode) }
      const result = JSON.parse(await responseText(response, 64_000))
      const actual = [result.actualQuality, result.selectedQuality, result.quality, result.type]
        .map(value => String(value ?? '').toLowerCase()).find(value => musicQualities.has(value))
      if (actual && actual !== quality && !(isSpatial(actual) && isSpatial(quality))) throw Error('解析源返回了其他音质')
      const url = mediaUrl(typeof result.url === 'string' ? result.url.trim().replace(/^\/\//, 'https://') : result.url)
      if (/\/404|[?&]status=404\b|error\.html|jd-musicrep-ts|\/musicrep-ts\/|-ts\/|\/rs02|trialinfo|freetrial/i.test(url.href)) {
        throw Error('解析源返回了失效链接或试听片段')
      }
      const headers = validHeaders(result.headers)
      const media = { url: url.href, headers, mediaType: result.mediaType === 'hls' ? 'hls' : '' }
      const deadline = new AbortController()
      const timer = setTimeout(() => deadline.abort(), 10_000)
      try {
        const candidate = await openExternal(url, { headers }, AbortSignal.any([signal, deadline.signal]))
        return { media, source: await inspectMusicResponse(candidate, media, quality) }
      } finally { clearTimeout(timer) }
    } catch (error) { lastError = error }
  }
  throw lastError
}

function isSpatial(quality) {
  return quality === 'atmos' || quality === 'atmos_plus'
}

function incompatibleEncoding(quality, encoding) {
  return encoding < 0 && ['flac', 'flac24bit', 'hires'].includes(quality)
    || encoding > 0 && ['128k', '192k', '320k'].includes(quality)
}

function detectAudio(prefix) {
  const at = (text, offset = 0) => offset >= 0 && prefix.toString('latin1', offset, offset + text.length) === text
  let offset = 0
  if (at('ID3') && prefix.length >= 10) {
    if (prefix.subarray(6, 10).some(value => value > 127)) return {}
    offset = prefix.subarray(6, 10).reduce((size, value) => (size << 7) | (value & 127), 0)
      + (prefix[3] === 4 && (prefix[5] & 16) !== 0 ? 20 : 10)
  }
  if (at('fLaC', offset)) return { extension: 'flac', encoding: 1 }
  if ((at('RIFF', offset) || at('RF64', offset)) && at('WAVE', offset + 8)) return { extension: 'wav' }
  if (at('ftyp', offset + 4)) return { extension: 'm4a' }
  if (at('OggS', offset)) {
    const packet = offset + 27 + prefix[offset + 26]
    if (at('OpusHead', packet)) return { extension: 'opus', encoding: -1 }
    if (at('\x01vorbis', packet)) return { extension: 'ogg', encoding: -1 }
    return { extension: 'ogg' }
  }
  if (offset <= prefix.length - 7 && prefix[offset] === 255) {
    const second = prefix[offset + 1], third = prefix[offset + 2]
    const aacSize = ((prefix[offset + 3] & 3) << 11) | (prefix[offset + 4] << 3) | (prefix[offset + 5] >>> 5)
    if ((second & 0xf6) === 0xf0 && ((third >>> 2) & 15) < 13 && aacSize >= 7) return { extension: 'aac', encoding: -1 }
    if ((second & 0xe0) === 0xe0 && (second & 0x18) !== 8 && (second & 6) !== 0
      && (third & 0xf0) !== 0xf0 && (third & 12) !== 12) return { extension: 'mp3', encoding: -1 }
  }
  if (offset !== 0) return {}
  const text = prefix.toString('utf8', 0, 96).trimStart()
  if (/^#EXTM3U/i.test(text)) return { hls: true }
  return { invalid: /^[<{\[]/.test(text) }
}

async function inspectMusicResponse(source, media, quality) {
  const { response } = source
  try {
    if (response.statusCode !== 200) throw Error('音频地址 HTTP ' + response.statusCode)
    const prefixChunks = []
    let length = 0
    for await (const part of response.iterator({ destroyOnReturn: false })) {
      prefixChunks.push(part)
      length += part.length
      if (length >= 16_384) break
    }
    const prefix = Buffer.concat(prefixChunks).subarray(0, 16_384)
    const actual = detectAudio(prefix)
    const mime = (response.headers['content-type'] || '').split(';')[0].trim().toLowerCase()
    const reported = types.get(mime)
    const fallbackEncoding = ['mp3', 'aac', 'opus'].includes(reported) || mime === 'audio/vorbis' ? -1
      : ['flac', 'wav'].includes(reported) ? 1 : 0
    const hls = actual.hls || (!actual.extension && (media.mediaType === 'hls'
      || /\.m3u8$/i.test(source.url.pathname) || mime.includes('mpegurl')))
    const audio = actual.extension || hls || mime.startsWith('audio/') || types.has(mime)
      || ['', 'application/octet-stream', 'binary/octet-stream', 'application/download'].includes(mime)
    if (!length || !audio || actual.invalid || (!hls && length < 1024)) throw Error('音源没有返回有效的音频文件')
    if (incompatibleEncoding(quality, actual.extension || hls ? actual.encoding || 0 : fallbackEncoding)) {
      throw Error(actual.extension ? `文件实际是 ${actual.extension.toUpperCase()}，与所选音质不符` : `无法确认所选音质，媒体响应标为 ${mime}`)
    }
    const stream = Readable.from((async function* () {
      try { yield* prefixChunks; yield* response }
      finally { response.destroy() }
    })(), { objectMode: false })
    return { ...source, stream, audio: { hls: Boolean(hls), extension: actual.extension } }
  } catch (error) {
    response.destroy()
    throw error
  }
}

function reserveQueueSlot() {
  if (stopping || submitting + [...jobs.values()].filter(job => ['queued', 'downloading'].includes(job.status)).length >= 200) {
    const error = Error(stopping ? '服务正在停止' : '下载队列已满')
    error.status = stopping ? 503 : 429
    throw error
  }
  submitting++
}

async function enqueue(body, song) {
  if (!body || !Object.hasOwn(roots, body.kind)) throw Error('Invalid download category')
  const createdAt = new Date().toISOString()
  const job = {
    id: randomUUID(), kind: body.kind,
    ...(song ? { song } : { url: mediaUrl(body.url).href, headers: validHeaders(body.headers) }),
    title: cleanName(body.title, 'audio'), collection: cleanName(body.collection, '未分类有声书'),
    artist: typeof body.artist === 'string' ? body.artist.slice(0, 180) : '',
    album: typeof body.album === 'string' ? body.album.slice(0, 180) : '',
    trackTitle: typeof body.trackTitle === 'string' ? body.trackTitle.slice(0, 180) : '',
    mediaType: body.mediaType === 'hls' ? 'hls' : '',
    quality: body.kind === 'music' && musicQualities.has(body.quality) ? body.quality : '',
    track: Number.isSafeInteger(body.track) && body.track > 0 ? body.track : null,
    createdAt, updatedAt: createdAt, status: 'queued', bytes: 0, total: 0,
  }
  reserveQueueSlot()
  try {
    await persist(job)
    jobs.set(job.id, job)
  } finally { submitting-- }
  schedule()
  return publicJob(job)
}

const retrying = new Set()
async function retryJob(id) {
  const job = jobs.get(id)
  if (!job) return null
  if (!publicJob(job).retryable || running.has(id) || retrying.has(id)) {
    const error = Error('此任务暂不可重试。已失效的推送请从手机重新发起。')
    error.status = 409
    throw error
  }
  const updated = { ...job, status: 'queued', bytes: 0, total: 0, error: undefined, updatedAt: new Date().toISOString() }
  reserveQueueSlot()
  retrying.add(id)
  try {
    await persist(updated)
    jobs.set(id, updated)
  } finally { retrying.delete(id); submitting-- }
  schedule()
  return publicJob(updated)
}

async function initializeDownloads() {
  for (const directory of [state, ...Object.values(roots)]) {
    try {
      await mkdir(directory, { recursive: true })
      await access(directory, constants.W_OK)
    } catch (error) { throw Error(`Directory must be writable: ${directory} (${error.code})`, { cause: error }) }
  }
  if (!embedded) {
    try { musicSources = JSON.parse(await readFile(sourcesFile, 'utf8')) }
    catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  for (const file of await readdir(state)) {
    if (!/^[0-9a-f-]{36}\.json$/.test(file)) continue
    const job = JSON.parse(await readFile(path.join(state, file), 'utf8'))
    if (job.status === 'downloading') job.status = 'queued'
    jobs.set(job.id, job)
  }
}

async function startStandalone() {
  const web = await readFile(new URL('./index.html', import.meta.url))
  const server = http.createServer((req, res) => void handleStandalone(req, res, web))
  server.listen(port, '0.0.0.0', () => console.log('YinLang downloader listening on port ' + port))
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => {
    stopping = true
    server.close()
    for (const controller of running.values()) controller.abort()
  })
}

async function handleStandalone(req, res, web) {
  if (!req.url?.startsWith('/') || req.url.startsWith('//')) return send(res, 400, { error: 'Invalid request path' })
  if (await handlePushRequest(req, res)) return
  const route = new URL(req.url, 'http://localhost')
  if (req.method === 'GET' && route.pathname === '/') {
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store',
      'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'",
    })
    return res.end(web)
  }
  if (!authorized(req)) return send(res, 401, { error: 'Invalid token' })
  try {
    if (req.method === 'GET' && route.pathname === '/v1/music/search') {
      const query = route.searchParams.get('q')?.trim()
      const source = route.searchParams.get('source') || 'all'
      const page = Math.max(1, Math.min(100, Number(route.searchParams.get('page')) || 1))
      if (!query || query.length > 180 || !['all', ...musicProviders].includes(source)) throw Error('Invalid search query')
      return send(res, 200, await globalThis.SoundShelfCatalog('search', { query, source, page }))
    }
    if (route.pathname === '/v1/music/sources' && req.method === 'GET') {
      return send(res, 200, { sources: musicSources })
    }
    if (route.pathname === '/v1/music/sources' && req.method === 'POST') {
      const input = await readBody(req)
      if (!musicProviders.includes(input?.platform)) throw Error('Invalid music platform')
      const source = {
        id: randomUUID(), name: cleanName(input.name, '音乐解析源'), platform: input.platform,
        endpoint: mediaUrl(input.endpoint).href,
      }
      await persistSources(current => {
        if (current.length >= 50) throw Error('Too many music sources')
        return [...current, source]
      })
      return send(res, 201, source)
    }
    if (route.pathname.startsWith('/v1/music/sources/') && req.method === 'DELETE') {
      const id = route.pathname.split('/').pop()
      await persistSources(current => current.filter(source => source.id !== id))
      return send(res, 200, { status: 'removed' })
    }
    if (req.method === 'POST' && route.pathname === '/v1/music/download') {
      const input = await readBody(req)
      const song = input?.song
      if (!song || !musicProviders.includes(song.source)) throw Error('Invalid music entry')
      if (!musicSources.some(source => source.platform === song.source)) throw Error('请先添加支持此平台的音乐解析源')
      const quality = input.quality ?? '320k'
      if (!musicQualities.has(quality)) throw Error('不支持所选音质')
      return send(res, 202, await enqueue({
        kind: 'music', title: [song.singer, song.name].filter(Boolean).join(' - '),
        artist: song.singer, album: song.albumName, trackTitle: song.name, quality,
      }, song))
    }
    const retry = req.method === 'POST' && /^\/v1\/jobs\/([0-9a-f-]{36})\/retry$/.exec(route.pathname)
    if (retry) {
      const job = await retryJob(retry[1])
      return job ? send(res, 202, job) : send(res, 404, { error: '任务不存在' })
    }
    return send(res, 404, { error: 'Not found' })
  } catch (error) {
    sendError(res, error)
  }
}

if (pushEnabled && !embedded) {
  await initializeDownloads()
  await startStandalone()
  schedule()
} else if (!pushEnabled) {
  console.warn('[yinlangserver] 手机推送未启用。需要至少 32 字符的 DOWNLOAD_TOKEN。')
}
