import { miguChartCovers } from './migu-chart-covers.js'

const platforms = ['wy', 'tx', 'kg', 'kw', 'mg']

const chartCoverFields = {
  wy: body => [body?.list, 'id'],
  tx: body => [body?.data?.topList, 'id'],
  kg: body => [body?.data?.info, 'rankid'],
  kw: body => [body?.child, 'sourceid'],
}

async function chartCovers(charts, source) {
  if (source === 'mg') return miguChartCovers(charts)
  const fields = chartCoverFields[source]
  if (!fields) return { list: [] }
  const response = await charts.getBoardsData()
  const [rows, idField] = fields(response?.body)
  if (response?.statusCode !== 200 || !Array.isArray(rows)) throw Error('平台未返回榜单封面')
  const list = rows.flatMap(row => {
    const id = row?.[idField]
    const cover = [row?.coverImgUrl, row?.picUrl, row?.imgurl, row?.imgUrl, row?.pic, row?.cover, row?.bannerurl]
      .find(value => typeof value === 'string' && /^(?:https?:)?\/\//i.test(value.trim()))
    return id && cover ? [{ id: String(id), cover: cover.trim().replaceAll('{size}', '400') }] : []
  })
  return { list }
}

export function makeCatalog(adapters, chartCover) {
  async function single(operation, source, args) {
    const [search, playlists, charts, lyric] = adapters[source]
    const page = Math.max(1, Number(args.page) || 1)
    let result
    switch (operation) {
      case 'lyric': {
        const music = args.music || {}
        const pending = source === 'wy' ? lyric(music.songmid || music.songId) : lyric.getLyric(music)
        const result = await pending.promise
        return { lyric: result?.lyric || '', tlyric: result?.tlyric || '' }
      }
      case 'search': result = await search.search(args.query, page, 20); break
      case 'lists': {
        const sorts = playlists.sortList || []
        const wanted = args.sort === '@hot' ? /热门|最热/ : args.sort === '@new' ? /最新/ : null
        const order = wanted ? sorts.find(row => wanted.test(row.name)) : sorts.find(row => String(row.id) === String(args.sort))
        const sort = order?.id ?? sorts[0]?.id ?? ''
        result = args.query ? await playlists.search(args.query, page, 20)
          : await playlists.getList(sort, args.tag || '', page)
        break
      }
      case 'tags': return { ...await playlists.getTags(), sorts: playlists.sortList || [] }
      case 'list': result = await playlists.getListDetail(args.id, page); break
      case 'charts': {
        result = await charts.getBoards()
        // The legacy European board now returns success with an empty
        // columnInfo. Do not keep offering an unopenable, coverless chart.
        if (source === 'mg') result = { ...result, list: result.list.filter(row => String(row.bangid) !== '19190036') }
        break
      }
      case 'chart-covers': return chartCovers(charts, source)
      case 'chart-cover': return chartCover(charts, source, args.id)
      case 'chart': result = await charts.getList(args.id, page); break
      default: throw Error('未知的音乐目录操作')
    }
    const data = Array.isArray(result) ? { list: result } : result
    if (!Array.isArray(data?.list)) throw Error('平台未返回有效的列表')
    const list = data.list.map(item => ({ ...item, source }))
    const more = operation !== 'charts' && (Number(data.total) > page * (Number(data.limit) || list.length) || Number(data.allPage) > page)
    return { ...data, list, more }
  }
  return async (operation, args = {}) => {
    if (platforms.includes(args.source)) return single(operation, args.source, args)
    if (args.source !== 'all' || !['search', 'lists', 'charts'].includes(operation)) throw Error('请选择音乐平台')
    const results = await Promise.allSettled(platforms.map(source => single(operation, source, args)))
    const lists = results.flatMap(result => result.status === 'fulfilled' ? [result.value.list] : [])
    if (!lists.length) throw Error('音乐平台暂时未返回结果')
    const list = []
    for (let i = 0; i < Math.max(...lists.map(rows => rows.length)); i++) {
      for (const rows of lists) if (rows[i]) list.push(rows[i])
    }
    const failed = platforms.filter((source, index) => results[index].status === 'rejected')
    return { list, failed, more: results.some(result => result.value?.more) }
  }
}
