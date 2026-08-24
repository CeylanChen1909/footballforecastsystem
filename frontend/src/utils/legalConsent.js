export const LEGAL_CONSENT_VERSION = '20260824-v1'
const KEY = 'football_legal_consent'

export function hasLegalConsent() {
  try { return window.localStorage.getItem(KEY) === LEGAL_CONSENT_VERSION } catch { return false }
}

export function saveLegalConsent() {
  try { window.localStorage.setItem(KEY, LEGAL_CONSENT_VERSION) } catch { /* storage disabled; gate remains visible until this tab succeeds */ }
}

export function clearLegalConsent() {
  try { window.localStorage.removeItem(KEY) } catch { /* ignore */ }
}
