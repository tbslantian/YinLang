// The PC index supplies artwork without downloading every chart's song list.
// These fixed category covers belong to older charts omitted from that index.
const categoryCovers = [
  ['75959118', 'https://d.musicapp.migu.cn/data/oss/column/00/1w/hh/a3b3d2a8bd4a4a2591c725072002bc2e.webp'],
  ['76557036', 'https://d.musicapp.migu.cn/data/oss/column/00/1w/wn/1735b0b2a28c465da9cbf1339cbc49e4.webp'],
  ['23189399', 'https://d.musicapp.migu.cn/data/oss/column/00/1w/gk/9a8e2695878f46b8861eb977653b597c.webp'],
  ['23189800', 'https://d.musicapp.migu.cn/data/oss/column/00/1w/go/6c65b4a087dd45c3b5c960c40fa54150.webp'],
]
const pending = new WeakMap()

export function miguChartCovers(charts) {
  const cached = pending.get(charts)
  if (cached && Date.now() - cached.time < 10 * 60 * 1000) return cached.promise
  const promise = (async () => {
    // Upstream stores a cancellable request on `this`; isolate it so unrelated
    // artwork requests cannot cancel each other or hit its stale request field.
    const isolated = Object.create(charts)
    isolated.requestBoardsObj = null
    const response = await isolated.getBoardsData()
    if (response?.statusCode !== 200 || response.body?.code !== '000000') throw Error('咪咕未返回榜单封面')
    const covers = new Map(categoryCovers)
    function visit(value) {
      if (Array.isArray(value)) { value.forEach(visit); return }
      if (!value || typeof value !== 'object') return
      const id = value.rankId || value.displayLogId?.param?.rankId
      const cover = value.imageUrl || value.imgUrl || value.picUrl
      if (id && typeof cover === 'string' && /^(?:https?:)?\/\//i.test(cover.trim())) {
        covers.set(String(id), cover.trim().replace(/^\/\//, 'https://'))
      }
      // Only walk board containers, never decode songData or request songs.
      for (const key of ['data', 'contents', 'contentItemList', 'itemList']) visit(value[key])
    }
    visit(response.body)
    return { list: [...covers].map(([id, cover]) => ({ id, cover })) }
  })()
  pending.set(charts, { time: Date.now(), promise })
  promise.catch(() => { if (pending.get(charts)?.promise === promise) pending.delete(charts) })
  return promise
}
