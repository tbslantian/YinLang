const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' }
export const decodeName = value => String(value ?? '').replace(/&(#x[\da-f]+|#\d+|\w+);/gi, (original, key) => {
  if (key[0] !== '#') return entities[key] ?? original
  const point = key[1] === 'x' ? parseInt(key.slice(2), 16) : Number(key.slice(1))
  return point <= 0x10ffff ? String.fromCodePoint(point) : original
})
export function formatPlayTime(value) {
  const seconds = Math.max(0, Math.floor(Number(value) || 0))
  return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0')
}
export function sizeFormate(bytes) {
  const amount = Number(bytes)
  return amount ? (amount / 1048576).toFixed(2) + 'M' : ''
}
export const formatPlayCount = value => Number(value) >= 10000 ? (Number(value) / 10000).toFixed(1) + '万' : String(value || '')
export function dateFormat(value, pattern = 'yyyy-MM-dd') {
  const date = value instanceof Date ? value : new Date(value)
  if (isNaN(date)) return ''
  return pattern.replace(/yyyy|MM|dd|HH|mm|ss/g, part => String({
    yyyy: date.getFullYear(), MM: date.getMonth() + 1, dd: date.getDate(),
    HH: date.getHours(), mm: date.getMinutes(), ss: date.getSeconds(),
  }[part]).padStart(2, '0'))
}
