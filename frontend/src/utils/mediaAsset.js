/**
 * Return a same-origin URL for externally hosted media.
 *
 * Team crests and portraits often come from CDN hosts that are unavailable
 * in a user's region or reject browser hotlinking.  Keeping the original URL
 * in the API payload is useful for attribution, while rendering through the
 * server proxy gives production a single cacheable and observable path.
 */
export const getMediaAssetUrl = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  if (raw.startsWith('/api/media/image?') || raw.startsWith('data:') || raw.startsWith('blob:')) return raw
  if (/^https?:\/\//i.test(raw)) return `/api/media/image?url=${encodeURIComponent(raw)}`
  return raw
}

export default getMediaAssetUrl
