const KEY = 'football_analytics_consent'

export function getAnalyticsConsent() {
  try { return window.localStorage.getItem(KEY) || '' } catch { return '' }
}

export function setAnalyticsConsent(value) {
  try { window.localStorage.setItem(KEY, value === 'granted' ? 'granted' : 'essential') } catch { /* storage disabled */ }
  window.dispatchEvent(new CustomEvent('football-consent-changed', { detail: { value } }))
}

export function clearAnalyticsConsent() {
  try { window.localStorage.removeItem(KEY) } catch { /* storage disabled */ }
  window.dispatchEvent(new CustomEvent('football-consent-changed', { detail: { value: '' } }))
}

export function canTrackAnalytics() {
  return getAnalyticsConsent() === 'granted'
}
