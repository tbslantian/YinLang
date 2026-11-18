(() => {
  // Bootstrap once, then run independent calls in the same discovery environment.
  globalThis.SoundShelfRuntime = {
    initialize(scripts) {
      Promise.resolve().then(() => { (0, eval)(scripts); Native.ready('') })
        .catch(error => Native.ready(String(error.message || error)))
    },
    execute(id, expression) {
      Promise.resolve().then(() => (0, eval)('(' + expression + ')'))
        .then(value => Native.result(JSON.stringify({ id, value })),
          error => Native.result(JSON.stringify({ id, error: String(error.message || error) })))
    },
  }
  const sources = new Map()
  const requests = new Map()
  let nextRequest = 0
  function unpack(raw) {
    const value = JSON.parse(raw)
    if (value.error) throw Object.assign(Error(value.error), { status: value.status || 0 })
    return value
  }
  function requestOptions(options = {}) {
    const method = String(options.method || 'GET').toUpperCase()
    const headers = { ...(options.headers || {}) }
    let body = options.body
    if (body != null && typeof body !== 'string') body = new URLSearchParams(body).toString()
    if (body != null && !Object.keys(headers).some(key => key.toLowerCase() === 'content-type')) {
      headers['Content-Type'] = options.contentType || 'application/x-www-form-urlencoded'
    }
    return { ...options, method, headers, body }
  }
  function networkResponse(raw, check = true) {
    const response = unpack(raw)
    if (check && (response.status < 200 || response.status >= 300)) throw Object.assign(Error('HTTP ' + response.status), { status: response.status })
    return response
  }
  function network(url, options, check) {
    return networkResponse(Native.network(url, JSON.stringify(requestOptions(options))), check)
  }
  function parseJson(text) {
    text = String(text).replace(/^\uFEFF/, '')
    try { return JSON.parse(text) } catch (error) {
      const jsonp = text.match(/^\s*[\w.$]+\s*\(([\s\S]*)\)\s*;?\s*$/)
      if (!jsonp) throw error
      return JSON.parse(jsonp[1])
    }
  }
  function at(value, path) {
    if (path == null || path === '') return value ?? null
    for (const key of String(path).split('.')) {
      if (value == null || !Object.prototype.hasOwnProperty.call(value, key)) return null
      value = value[key]
    }
    return value ?? null
  }
  function str(value, path) {
    const found = at(value, path)
    return found == null ? '' : String(found)
  }
  function int(value, path, fallback = 0) {
    const found = Number.parseInt(str(value, path), 10)
    return Number.isNaN(found) ? fallback : found
  }
  function items(value, path) {
    const found = at(value, path)
    return Array.isArray(found) ? found : []
  }
  function regexInt(value, pattern, fallback = 0) {
    const match = RegExp(pattern).exec(String(value ?? ''))
    return int(match?.[1], '', fallback)
  }
  Object.assign(globalThis, { parseJson, at, str, int, items, regexInt })
  function htmlDecode(text) {
    return new DOMParser().parseFromString('<!doctype html><body>' + String(text || ''), 'text/html').body.textContent
  }
  function element(node, baseUrl) {
    function select(selector) {
      if (!node) return []
      const text = selector.match(/:contains\(([^)]+)\)/)
      const query = text ? selector.replace(text[0], '') || '*' : selector
      return Array.from(node.querySelectorAll(query)).filter(found => !text || found.textContent.includes(text[1])).map(found => element(found, baseUrl))
    }
    const attr = name => node?.getAttribute?.(name) || ''
    return {
      attr, exists: () => !!node, tag: () => node?.tagName?.toLowerCase() || '',
      hasClass: name => node?.classList?.contains(name) || false,
      byId: id => element((node?.ownerDocument || node)?.getElementById?.(id), baseUrl),
      select, first: query => select(query)[0] || element(null, baseUrl),
      text: () => (node?.body || node)?.textContent?.replace(/\s+/g, ' ').trim() || '',
      ownText: () => Array.from(node?.childNodes || []).filter(child => child.nodeType === 3).map(child => child.nodeValue).join(' ').replace(/\s+/g, ' ').trim(),
      html: () => node?.innerHTML || '', outerHtml: () => node?.outerHTML || '',
      parent: () => element(node?.parentElement, baseUrl),
      children: () => Array.from(node?.children || []).map(child => element(child, baseUrl)),
      absUrl(name) {
        try { const value = attr(name); return value && new URL(value, baseUrl).href } catch { return '' }
      },
    }
  }
  const parseHtml = (html, url) => element(new DOMParser().parseFromString(html, 'text/html'), url)
  function host(id) {
    return {
      getString: (url, options) => network(url, options).body,
      getJson: (url, options) => parseJson(network(url, options).body),
      getHtml(url, options) {
        const response = network(url, options)
        return parseHtml(response.body, response.url)
      },
      getStrings(urls, options, concurrency = 4) {
        const requests = urls.map((url, index) => ({ url, options: requestOptions(Array.isArray(options) ? options[index] : options) }))
        const { values } = unpack(Native.networkBatch(JSON.stringify(requests), concurrency))
        return values.map(raw => { try { return networkResponse(raw).body } catch { return null } })
      },
      fetch: (url, options) => network(url, options, false),
      status: (url, options) => network(url, { ...options, statusOnly: true }, false).status,
      finalUrl: (url, options) => network(url, { ...options, statusOnly: true }, false).url,
      parseHtml, htmlDecode,
      render(url, options = {}) {
        const { values } = unpack(Native.probe(url, JSON.stringify(options), false))
        for (const value of values) {
          const result = options.parse ? options.parse(value) : value
          if (result) return result
        }
        throw Error('网页未返回可用内容')
      },
      sniff(url, options = {}) {
        const { values } = unpack(Native.probe(url, JSON.stringify(options), true))
        const validate = options.validate || (value => /\.(mp3|m4a|aac|ogg|flac|wav|m3u8)(?:$|[?#])/i.test(value))
        const media = values.find(validate)
        if (!media) throw Error('网页未返回可用音频')
        return media
      },
      cookies: url => Native.cookies(url),
      getPref(idKey, fallback) { const value = Native.get(id + ':pref:' + idKey); return value ? JSON.parse(value) : fallback },
      setPref(idKey, value) { Native.put(id + ':pref:' + idKey, JSON.stringify(value)) },
      readCache: name => Native.readCache(id + ':' + name),
      writeCache: (name, text) => Native.writeCache(id + ':' + name, String(text)),
      cacheAge: name => Native.cacheAge(id + ':' + name),
      userAgent: desktop => Native.agent(Boolean(desktop)),
      webViewUA: () => Native.agent(false),
      toast: message => Native.notice(String(message)),
      progress() {},
      log() {},
      sleep: delay => Native.sleep(Number(delay)),
      uuid: () => Native.uuid(),
      md5: value => Native.digest('MD5', String(value)),
      sha256: value => Native.digest('SHA-256', String(value)),
      base64Decode: value => Native.decode64(String(value)),
      base64Encode: value => Native.encode64(String(value)),
      aesEcbDecrypt: (data, key) => Native.decrypt(data, key),
      playsAnyDownload: true,
    }
  }
  globalThis.registerSource = source => {
    if (!source?.id || !source.name) throw Error('听书源缺少 ID 或名称')
    source.host = host(source.id)
    sources.set(source.id, source)
  }
  globalThis.bookSources = () => [...sources.values()].map(source => ({
    id: source.id, name: source.name, description: source.description || '', group: source.group || '听书',
    enabled: source.enabledByDefault !== false, search: typeof source.search === 'function',
    discover: typeof source.categoryMenus === 'function', loginUrl: source.loginUrl || '',
    config: Array.isArray(source.config) ? source.config : [],
  }))
  globalThis.bookCall = (id, action, args) => {
    const source = sources.get(id)
    if (!source) throw Error('找不到这个听书源')
    if (action === 'audio') {
      const value = source.audio ? source.audio(args[0]) : args[0]
      const result = typeof value === 'string' ? { url: value } : value
      const url = typeof result?.url === 'string' ? result.url.trim().replace(/^\/\//, 'https://') : ''
      if (!/^https?:\/\//i.test(url)) throw Error('听书源未返回可播放的 HTTP 音频地址')
      const headers = new Map()
      for (const group of [source.audioHeaders?.(url), result.headers]) {
        for (const [name, header] of Object.entries(group || {})) headers.set(name.toLowerCase(), [name, header])
      }
      if (!headers.has('user-agent')) headers.set('user-agent', ['User-Agent', source.host.userAgent(false)])
      if (!headers.has('cookie')) {
        const cookie = source.host.cookies(url)
        if (cookie) headers.set('cookie', ['Cookie', cookie])
      }
      return { ...result, url, headers: Object.fromEntries(headers.values()) }
    }
    if (action === 'setPrefs') {
      for (const [key, value] of Object.entries(args[0])) source.host.setPref(key, value)
      return true
    }
    if (typeof source[action] !== 'function') throw Error('这个听书源不支持此操作')
    if (action === 'bookDetail') {
      const paged = source.multipleEpisodePages === true
      const detail = source.bookDetail(args[0], args[1], !paged || args[2])
      if (!paged) return detail
      return Promise.resolve(detail).then(value => value && { ...value, _multipleEpisodePages: true })
    }
    return source[action](...args)
  }
  globalThis.catalogTransport = (url, options, signal) => new Promise((resolve, reject) => {
    const id = ++nextRequest
    if (signal?.aborted) return reject(Error('请求已取消'))
    requests.set(id, { resolve, reject })
    signal?.addEventListener('abort', () => { requests.delete(id); reject(Error('请求已取消')) }, { once: true })
    Native.networkAsync(id, url, JSON.stringify(options))
  })
  globalThis.networkComplete = (id, raw) => {
    const pending = requests.get(id)
    if (!pending) return
    requests.delete(id)
    try { pending.resolve(unpack(raw)) } catch (error) { pending.reject(error) }
  }
  let lxHandler
  let lxSources
  let lxAlert
  let onLxReady
  let rejectLxReady
  const lxReady = new Promise((resolve, reject) => { onLxReady = resolve; rejectLxReady = reject })
  lxReady.catch(() => {})
  const fromBase64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0))
  function bytes(value, encoding = 'utf8') {
    if (typeof value !== 'string') return Uint8Array.from(value || [])
    if (encoding === 'base64') return fromBase64(value)
    if (encoding === 'hex') return Uint8Array.from(value.match(/[\da-f]{2}/gi) || [], part => parseInt(part, 16))
    if (encoding === 'binary' || encoding === 'latin1') return Uint8Array.from(value, character => character.charCodeAt(0))
    return new TextEncoder().encode(value)
  }
  function asString(value, encoding = 'utf8') {
    const data = bytes(value)
    if (encoding === 'hex') return Array.from(data, byte => byte.toString(16).padStart(2, '0')).join('')
    if (encoding === 'base64' || encoding === 'binary' || encoding === 'latin1') {
      let text = ''
      for (let index = 0; index < data.length; index += 8192) text += String.fromCharCode(...data.subarray(index, index + 8192))
      return encoding === 'base64' ? btoa(text) : text
    }
    return new TextDecoder().decode(data)
  }
  globalThis.lx = {
    version: '2.0.0', env: 'mobile',
    EVENT_NAMES: { inited: 'inited', request: 'request', updateAlert: 'updateAlert' },
    on(event, callback) { if (event === 'request') lxHandler = callback },
    send(event, payload) {
      if (event === 'updateAlert' && payload && !lxAlert) {
        lxAlert = { log: String(payload.log || '').slice(0, 1024), updateUrl: String(payload.updateUrl || '').slice(0, 1024) }
        Native.updateAlert(lxAlert.log, lxAlert.updateUrl)
        return
      }
      if (event !== 'inited') return
      if (payload?.status === false || !payload?.sources) rejectLxReady(Error('LX 音源初始化失败'))
      else { lxSources = payload.sources; onLxReady(lxSources) }
    },
    request(url, options, callback) {
      const controller = new AbortController()
      const headers = { ...options?.headers }
      let body = options?.body
      if (options?.form) {
        body = new URLSearchParams(options.form).toString()
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
      } else if (body != null && typeof body !== 'string') {
        body = JSON.stringify(body)
        headers['Content-Type'] ??= 'application/json'
      }
      catalogTransport(url, { method: String(options?.method || 'GET').toUpperCase(), headers, body }, controller.signal)
        .then(response => {
          let data = response.body
          try { data = parseJson(data) } catch {}
          callback(null, { statusCode: response.status, headers: response.headers, body: data }, data)
        }, error => callback(error))
      return () => controller.abort()
    },
    utils: {
      crypto: {
        md5: value => Native.digest('MD5', String(value)),
        randomBytes: length => fromBase64(Native.random(length)),
        aesEncrypt: (value, mode, key, iv) => fromBase64(unpack(Native.encrypt(mode, asString(value, 'base64'), asString(key, 'base64'), asString(iv, 'base64'))).value),
        rsaEncrypt: (value, key) => fromBase64(unpack(Native.rsa(asString(value, 'base64'), typeof key === 'string' ? key : key.key)).value),
      },
      zlib: {
        inflate: async value => fromBase64(unpack(Native.zip(asString(value, 'base64'), true)).value),
        deflate: async value => fromBase64(unpack(Native.zip(asString(value, 'base64'), false)).value),
      },
      buffer: { from: bytes, bufToString: asString },
    },
  }
  globalThis.lxPlatforms = async () => {
    if (!lxSources) {
      let timer
      try {
        await Promise.race([lxReady, new Promise((resolve, reject) => { timer = setTimeout(() => reject(Error('音源没有完成 LX 初始化')), 15000) })])
      } finally { clearTimeout(timer) }
    }
    if (!lxHandler || !lxSources) throw Error('音源没有完成 LX 初始化')
    return lxSources
  }
  globalThis.lxInstallation = async () => ({ platforms: await lxPlatforms(), alert: lxAlert })
  const qualityCodes = new Set(['128k', '192k', '320k', 'flac', 'flac24bit', 'hires', 'atmos', 'atmos_plus', 'master'])
  globalThis.lxResolve = async (source, quality, musicInfo) => {
    const platform = (await lxPlatforms())[source]
    if (!platform) throw Error('当前音源不支持这个音乐平台')
    if (platform.qualitys?.length && !platform.qualitys.includes(quality)) throw Error('当前音源不提供所选音质')
    const value = await lxHandler({ source, action: 'musicUrl', info: { type: quality, quality, musicInfo } })
    const result = typeof value === 'string' ? { url: value } : value
    const url = typeof result?.url === 'string' ? result.url.trim().replace(/^\/\//, 'https://') : ''
    if (!/^https?:\/\//i.test(url)) throw Error('音源未返回有效的 HTTP 音频地址')
    if (/\/404|[?&]status=404\b|error\.html/i.test(url)) throw Error('音源返回了失效的音频地址')
    if (/jd-musicrep-ts|\/musicrep-ts\/|-ts\/|\/rs02|trialinfo|freetrial/i.test(url)) throw Error('音源返回了试听片段')
    const reported = [result.actualQuality, result.selectedQuality, result.quality, result.type]
      .map(value => String(value ?? '').toLowerCase()).find(value => qualityCodes.has(value))
    if (reported && reported !== quality && !(['atmos', 'atmos_plus'].includes(quality) && ['atmos', 'atmos_plus'].includes(reported))) {
      throw Error('音源返回音质 ' + reported + '，与请求的 ' + quality + ' 不符')
    }
    return { ...result, url, selectedQuality: reported || quality }
  }
})()
