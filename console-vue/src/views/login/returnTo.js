export function safeReturn(value) {
  if (typeof value !== 'string') {
    return ''
  }
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\') || value.includes('://')) {
    return ''
  }
  if (value.startsWith('/login')) {
    return ''
  }
  return value
}
