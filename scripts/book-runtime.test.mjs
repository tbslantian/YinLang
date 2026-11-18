import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'

const host = fs.readFileSync(new URL('../shared/plugin-host.js', import.meta.url), 'utf8')
function runtime() {
  let ready, readyCount = 0
  const pending = new Map()
  const context = vm.createContext({ URL, URLSearchParams, Native: {
    ready(error) { readyCount++; ready(error) },
    result(raw) {
      const result = JSON.parse(raw)
      pending.get(result.id)(result)
      pending.delete(result.id)
    },
  } })
  vm.runInContext(host, context)
  return {
    context,
    readyCount: () => readyCount,
    initialize(scripts) {
      return new Promise(resolve => {
        ready = resolve
        context.SoundShelfRuntime.initialize(scripts)
      })
    },
    execute(id, expression) {
      return new Promise(resolve => {
        pending.set(id, resolve)
        context.SoundShelfRuntime.execute(id, expression)
      })
    },
  }
}

test('menus, pages and switching sources reuse registration and source state', async () => {
  const engine = runtime()
  assert.equal(await engine.initialize(`
    globalThis.registrations = (globalThis.registrations || 0) + 1;
    ['one', 'two'].forEach(id => registerSource({
      id, name: id, visited: 0,
      categoryMenus() { this.visited++; return [{ tabs: [{ url: id + ':category' }] }] },
      categoryPage(url) { return { books: [{ bookUrl: id + ':book', title: url, visits: this.visited }] } }
    }));
  `), '')
  const menus = await engine.execute(1, 'bookCall("one","categoryMenus",[])')
  assert.equal(menus.value[0].tabs[0].url, 'one:category')
  const page = await engine.execute(2, 'bookCall("one","categoryPage",["one:category"])')
  assert.equal(page.value.books[0].visits, 1)
  await engine.execute(3, 'bookCall("two","categoryMenus",[])')
  const other = await engine.execute(4, 'bookCall("two","categoryPage",["two:category"])')
  assert.equal(other.value.books[0].bookUrl, 'two:book')
  const returned = await engine.execute(5, 'bookCall("one","categoryPage",["one:next"])')
  assert.equal(returned.value.books[0].visits, 1)
  assert.equal(engine.context.registrations, 1)
  assert.equal(engine.readyCount(), 1)
})

test('operation failures retain call identity and allow the next operation', async () => {
  const engine = runtime()
  await engine.initialize('globalThis.answer = 42')
  const failed = await engine.execute(7, 'Promise.reject(Error("source unavailable"))')
  assert.deepEqual(failed, { id: 7, error: 'source unavailable' })
  assert.deepEqual(await engine.execute(8, 'answer'), { id: 8, value: 42 })
})

test('late asynchronous results carry their original call identity', async () => {
  const engine = runtime()
  await engine.initialize('')
  const old = engine.execute(1, 'new Promise(resolve => { globalThis.releaseOld = resolve })')
  assert.deepEqual(await engine.execute(2, '"new source"'), { id: 2, value: 'new source' })
  engine.context.releaseOld('old source')
  assert.deepEqual(await old, { id: 1, value: 'old source' })
})

test('plugin initialization failure is reported without running an operation', async () => {
  const engine = runtime()
  assert.equal(await engine.initialize('throw Error("broken plugin")'), 'broken plugin')
  assert.equal(engine.readyCount(), 1)
})
