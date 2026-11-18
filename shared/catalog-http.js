import { Buffer } from 'buffer/'

export function httpFetch(url, options = {}) {
  const controller = new AbortController()
  const headers = { ...(options.headers || {}) }
  let body = options.body
  if (options.form) {
    body = new URLSearchParams(options.form).toString()
    headers['Content-Type'] = 'application/x-www-form-urlencoded'
  } else if (body && typeof body === 'object') {
    body = JSON.stringify(body)
    if (!Object.keys(headers).some(key => key.toLowerCase() === 'content-type')) headers['Content-Type'] = 'application/json'
  }
  const promise = Promise.resolve().then(async () => {
    const response = await globalThis.catalogTransport(url, {
      method: (options.method || 'GET').toUpperCase(), headers, body,
      binary: /^https?:\/\/mlyric\.kuwo\.cn\/mobi\.s\?/.test(url),
      follow: options.followRedirect !== false && options.follow_max !== 0 && options.redirect !== 'manual',
    }, controller.signal)
    let data = response.body
    if (typeof data === 'string') {
      try { data = JSON.parse(data) } catch {
        const wrapped = data.match(/^\s*[\w.$]+\s*\(([\s\S]*)\)\s*;?\s*$/)
        if (wrapped) try { data = JSON.parse(wrapped[1]) } catch {}
      }
    }
    return { statusCode: response.status, body: data, headers: response.headers || {}, url: response.url,
      raw: response.raw ? Buffer.from(response.raw, 'base64') : undefined }
  })
  const cancel = () => controller.abort()
  return { promise, cancel, cancelHttp: cancel }
}
