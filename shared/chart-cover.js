import { miguChartCovers } from './migu-chart-covers.js'

// Artwork lookup must never call the normal, full-chart song loader.
export function makeChartCoverLoader({ httpFetch, weapi }) {
  return async (charts, source, id) => {
    let cover = ''
    if (source === 'wy') {
      const { statusCode, body } = await httpFetch('https://music.163.com/weapi/v3/playlist/detail', {
        method: 'post', form: weapi({ id, n: 1, p: 1 }),
      }).promise
      if (statusCode !== 200 || body?.code !== 200) throw Error('未能获取榜首封面')
      // Do not resolve playlist.trackIds: that would fetch details for every song.
      cover = body.playlist?.tracks?.[0]?.al?.picUrl || ''
    } else if (source === 'tx') {
      const period = charts.periods[id]?.period || await charts.getPeriods(id)
      const { statusCode, body } = await charts.listDetailRequest(Number(id), period, 1)
      if (statusCode !== 200 || body?.code !== 0) throw Error('未能获取榜首封面')
      const track = body.toplist?.data?.songInfoList?.[0]
      if (track) cover = charts.filterData([track])[0]?.img || ''
    } else if (source === 'kg') {
      const { statusCode, body } = await charts.getData(charts.getUrl(1, id, 1))
      if (statusCode !== 200 || body?.errcode !== 0) throw Error('未能获取榜首封面')
      const track = body.data?.info?.[0]
      if (track) cover = charts.filterData([track])[0]?.img || ''
    } else if (source === 'mg') {
      const result = await miguChartCovers(charts)
      cover = result.list.find(row => row.id === String(id).replace(/^mg__/, ''))?.topCover || ''
    } else if (source === 'kw') {
      // Isolate the limit; concurrent chart browsing must keep its normal page size.
      const single = Object.create(charts)
      single.limit = 1
      const data = await single.getList(id, 1)
      cover = data?.list?.[0]?.img || ''
    }
    // Sources without a one-song endpoint keep the placeholder instead of fetching a full chart.
    const artwork = typeof cover === 'string' ? cover.trim().replace(/^\/\//, 'https://').replaceAll('{size}', '400') : ''
    return { list: artwork ? [{ img: artwork, source }] : [] }
  }
}
