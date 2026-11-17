export function linkRows(records) {
  return records.map(row => {
    let url = row.fullShortUrl || ''
    if (row.kind === 'CHANNEL' && url) {
      const parsed = new URL(url)
      parsed.searchParams.set('c', row.source)
      url = parsed.toString()
    }
    return { ...row, key: row.entryKey, url,
      name: row.kind === 'MAIN' ? `${String(row.name || '').split(' · ')[0]} · 主链` : row.name,
      kind: { MAIN: '主链', CHANNEL: '渠道分支', PLAIN: '普通短链' }[row.kind],
      on: row.onFlag === true, effective: row.effectiveFlag === true }
  })
}
