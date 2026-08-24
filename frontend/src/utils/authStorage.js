// Access/refresh tokens are session-scoped by default. This reduces the
// lifetime of a token stolen through a browser injection and avoids leaving
// credentials on shared machines. A one-time migration removes legacy
// localStorage tokens created by older releases.
const SESSION_KEYS = ['football_token', 'football_refresh_token', 'football_user']

function migrateLegacy(key) {
  const current = window.sessionStorage.getItem(key)
  if (current) return current
  const legacy = window.localStorage.getItem(key)
  if (legacy) {
    window.sessionStorage.setItem(key, legacy)
    window.localStorage.removeItem(key)
  }
  return legacy
}

export const authStorage = {
  get(key) { return migrateLegacy(key) },
  set(key, value) { window.sessionStorage.setItem(key, value) },
  remove(key) {
    window.sessionStorage.removeItem(key)
    window.localStorage.removeItem(key)
  },
  clear() { SESSION_KEYS.forEach(key => this.remove(key)) }
}

