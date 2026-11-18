import test from 'node:test'
import assert from 'node:assert/strict'
import { makeChartCoverLoader } from './chart-cover.js'
import { makeCatalog } from './catalog-api.js'

test('NetEase cover request asks for one song and never expands trackIds', async () => {
  const calls = []
  const topCover = makeChartCoverLoader({
    weapi: data => data,
    httpFetch: (url, options) => {
      calls.push({ url, ...options })
      return { promise: Promise.resolve({ statusCode: 200, body: { code: 200, playlist: {
        tracks: [{ al: { picUrl: 'https://p1.music.126.net/top.jpg' } }, { al: { picUrl: 'https://p1.music.126.net/second.jpg' } }],
        trackIds: Array.from({ length: 1000 }, (_, id) => ({ id })),
      } } }) }
    },
  })
  const charts = { getList: () => assert.fail('Full chart loader called') }
  const catalog = makeCatalog({ wy: [null, null, charts] }, topCover)
  const result = await catalog('chart-cover', { source: 'wy', id: '3779629' })
  assert.deepEqual(result.list, [{ img: 'https://p1.music.126.net/top.jpg', source: 'wy' }])
  assert.equal(calls.length, 1)
  assert.deepEqual(calls[0].form, { id: '3779629', n: 1, p: 1 })
})

test('Missing top cover does not try another song or bulk song details', async () => {
  let requests = 0
  const load = makeChartCoverLoader({ weapi: data => data, httpFetch: () => {
    requests++
    return { promise: Promise.resolve({ statusCode: 200, body: { code: 200, playlist: {
      tracks: [{ al: {} }, { al: { picUrl: 'https://example.com/second.jpg' } }], trackIds: [{ id: 1 }, { id: 2 }],
    } } }) }
  } })
  assert.deepEqual(await load({}, 'wy', '1'), { list: [] })
  assert.equal(requests, 1)
})

test('QQ requests one song and extracts only rank one', async () => {
  const charts = {
    periods: { 26: { period: '2026_46' } },
    listDetailRequest: async (...args) => {
      assert.deepEqual(args, [26, '2026_46', 1])
      return { statusCode: 200, body: { code: 0, toplist: { data: { songInfoList: [{ img: 'top' }, { img: 'second' }] } } } }
    },
    filterData: tracks => { assert.equal(tracks.length, 1); return tracks },
  }
  assert.deepEqual((await makeChartCoverLoader({})(charts, 'tx', '26')).list, [{ img: 'top', source: 'tx' }])
})

test('Kugou requests page one with page size one', async () => {
  const charts = {
    getUrl: (...args) => { assert.deepEqual(args, [1, '8888', 1]); return 'one-song' },
    getData: async url => {
      assert.equal(url, 'one-song')
      return { statusCode: 200, body: { errcode: 0, data: { info: [{ img: 'top' }, { img: 'second' }] } } }
    },
    filterData: tracks => { assert.equal(tracks.length, 1); return tracks },
  }
  assert.deepEqual((await makeChartCoverLoader({})(charts, 'kg', '8888')).list, [{ img: 'top', source: 'kg' }])
})

test('Kuwo one-song limit is isolated from concurrent full chart browsing', async () => {
  const charts = { limit: 100, async getList(id, page) {
    assert.equal(this.limit, 1)
    assert.equal(charts.limit, 100)
    assert.equal(id, '93')
    assert.equal(page, 1)
    return { list: [{ img: 'top' }, { img: 'second' }] }
  } }
  assert.deepEqual((await makeChartCoverLoader({})(charts, 'kw', '93')).list, [{ img: 'top', source: 'kw' }])
  assert.equal(charts.limit, 100)
})

test('Unsupported lightweight source keeps placeholder without loading a full chart', async () => {
  assert.deepEqual(await makeChartCoverLoader({})({ getList: () => assert.fail('Full chart requested') }, 'mg', '1'), { list: [] })
})

test('Provider failure stops without falling back to a full chart', async () => {
  const load = makeChartCoverLoader({ weapi: data => data, httpFetch: () => ({ promise: Promise.resolve({ statusCode: 403 }) }) })
  await assert.rejects(load({ getList: () => assert.fail('Full chart requested') }, 'wy', '1'), /未能获取榜首封面/)
})
