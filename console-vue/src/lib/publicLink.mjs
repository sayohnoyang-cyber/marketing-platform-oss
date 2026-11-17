const loopback = new Set(['127.0.0.1', 'localhost', '[::1]'])

export function publicShortUrl(value, publicOrigin = '') {
  if (!value) return value
  try {
    const url = new URL(/^[a-z][a-z\d+.-]*:\/\//i.test(value) ? value : `http://${value}`)
    if (!['http:', 'https:'].includes(url.protocol)) return value
    if (publicOrigin && loopback.has(url.hostname)) {
      const base = new URL(publicOrigin)
      if (['http:', 'https:'].includes(base.protocol)) {
        return base.origin + url.pathname + url.search + url.hash
      }
    }
    return url.href
  } catch { return value }
}

export function normalizePublicLinks(value, publicOrigin) {
  if (Array.isArray(value)) return value.map(item => normalizePublicLinks(item, publicOrigin))
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key,
    key === 'fullShortUrl' && typeof item === 'string' ? publicShortUrl(item, publicOrigin) : normalizePublicLinks(item, publicOrigin)
  ]))
}

// Only complete web URLs are navigable; unavailable or malformed entries stay text.
export function webLinkHref(value) {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch { return '' }
}
