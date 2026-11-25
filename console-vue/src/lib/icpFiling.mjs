const PUBLIC_SECURITY_PORTAL = 'https://beian.mps.gov.cn/'

export function filingLinks({ icpNumber, icpUrl, publicSecurityNumber } = {}) {
  const links = []
  const icp = String(icpNumber ?? '').trim()
  if (icp) {
    links.push({ text: icp, href: String(icpUrl || 'https://beian.miit.gov.cn/').trim() })
  }
  const security = String(publicSecurityNumber ?? '').trim()
  if (security) {
    const code = (security.match(/\d{5,}/) || [''])[0]
    links.push({
      text: security,
      href: code ? `${PUBLIC_SECURITY_PORTAL}#/query/webSearch?code=${code}` : PUBLIC_SECURITY_PORTAL
    })
  }
  return links
}
