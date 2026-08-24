/** Shared prediction payload helpers.  Keeping these at the API boundary avoids
 * coupling the report view to the backend's current JSON/string conventions. */
export const normalizeProbability = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return null
  return number > 1 ? number / 100 : number
}

export const normalizeProbabilities = (values = []) => {
  const raw = values.map(normalizeProbability).map(value => Number.isFinite(value) && value >= 0 ? value : 0)
  const total = raw.reduce((sum, value) => sum + value, 0)
  if (!total) return { home: 0, draw: 0, away: 0, valid: false }
  return {
    home: raw[0] / total,
    draw: raw[1] / total,
    away: raw[2] / total,
    valid: Math.abs(total - 1) < 0.01
  }
}

export const parseFeatureString = (raw) => {
  if (!raw || typeof raw !== 'string') return null
  const match = raw.trim().match(/^\{(.+)\}$/)
  if (!match) return null
  const result = {}
  match[1].split(/,\s*(?=[a-zA-Z_]+=)/).forEach(pair => {
    const index = pair.indexOf('=')
    if (index === -1) return
    result[pair.slice(0, index).trim()] = pair.slice(index + 1).trim()
  })
  return Object.keys(result).length ? result : null
}
