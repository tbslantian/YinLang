import { Buffer } from 'buffer/'

export const constants = { Z_SYNC_FLUSH: 2 }

export function inflate(bytes, options, callback) {
  if (typeof options === 'function') { callback = options; options = {} }
  const decode = async () => {
    if (typeof globalThis.Native?.inflateCatalog === 'function') {
      const response = JSON.parse(globalThis.Native.inflateCatalog(Buffer.from(bytes).toString('base64'),
        options.finishFlush === constants.Z_SYNC_FLUSH))
      if (response.error) throw Error(response.error)
      return Buffer.from(response.value, 'base64')
    }
    const decoded = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate'))
    return Buffer.from(await new Response(decoded).arrayBuffer())
  }
  decode().then(value => callback(null, value), error => callback(error))
}
