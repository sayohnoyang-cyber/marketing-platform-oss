import test from 'node:test'
import assert from 'node:assert/strict'
import { ICP_FILING_NUMBER, ICP_FILING_URL, PUBLIC_SECURITY_FILING_NUMBER } from '../src/config/icp.mjs'
import { filingLinks } from '../src/lib/icpFiling.mjs'

test('ICP footer uses the approved filing number and MIIT link', () => {
  assert.equal(ICP_FILING_NUMBER, '陕ICP备2026035037号-1')
  assert.equal(ICP_FILING_URL, 'https://beian.miit.gov.cn/')
  assert.equal(PUBLIC_SECURITY_FILING_NUMBER, '')
  assert.deepEqual(filingLinks({
    icpNumber: ICP_FILING_NUMBER,
    icpUrl: ICP_FILING_URL,
    publicSecurityNumber: PUBLIC_SECURITY_FILING_NUMBER
  }), [
    { text: '陕ICP备2026035037号-1', href: 'https://beian.miit.gov.cn/' }
  ])
})

test('public security filing stays hidden until configured, then links to the query page', () => {
  assert.deepEqual(filingLinks({ icpNumber: '', publicSecurityNumber: '  ' }), [])
  assert.deepEqual(filingLinks({
    icpNumber: '陕ICP备2026035037号-1',
    icpUrl: 'https://beian.miit.gov.cn/',
    publicSecurityNumber: '陕公网安备61010202000123号'
  }), [
    { text: '陕ICP备2026035037号-1', href: 'https://beian.miit.gov.cn/' },
    { text: '陕公网安备61010202000123号', href: 'https://beian.mps.gov.cn/#/query/webSearch?code=61010202000123' }
  ])
})
