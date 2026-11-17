import test from 'node:test'
import assert from 'node:assert/strict'
import { publicShortUrl, normalizePublicLinks, webLinkHref } from '../src/lib/publicLink.mjs'

test('local links become complete shareable URLs while retaining code and channel', () => {
  assert.equal(publicShortUrl('127.0.0.1:9100/s/abc123?c=wx', 'http://10.20.222.31:9100'), 'http://10.20.222.31:9100/s/abc123?c=wx')
  assert.equal(publicShortUrl('http://localhost:9100/s/abc123', 'http://10.20.222.31:9100'), 'http://10.20.222.31:9100/s/abc123')
  assert.equal(publicShortUrl('https://short.example/s/abc123', 'http://10.20.222.31:9100'), 'https://short.example/s/abc123')
})

test('response normalization reaches main and created links without changing original targets or IDs', () => {
  const data = { id: '123', mainLink: { fullShortUrl: '127.0.0.1:9100/s/abc123', originUrl: 'http://127.0.0.1:9100/a/123' }, rows: [{ fullShortUrl: '127.0.0.1:9100/s/xyz123' }] }
  const result = normalizePublicLinks(data, 'http://10.20.222.31:9100')
  assert.equal(result.mainLink.fullShortUrl, 'http://10.20.222.31:9100/s/abc123')
  assert.equal(result.rows[0].fullShortUrl, 'http://10.20.222.31:9100/s/xyz123')
  assert.equal(result.mainLink.originUrl, data.mainLink.originUrl)
  assert.equal(result.id, data.id)
  assert.equal(data.mainLink.fullShortUrl, '127.0.0.1:9100/s/abc123')
  assert.equal(publicShortUrl('short.example/s/abc123'), 'http://short.example/s/abc123')
})


test('clickable links retain the complete destination and reject non-web schemes', () => {
  assert.equal(webLinkHref('https://short.example/s/code?c=wx#details'), 'https://short.example/s/code?c=wx#details')
  assert.equal(webLinkHref('http://short.example/a/123'), 'http://short.example/a/123')
  for (const value of ['', undefined, '未生成', 'javascript:alert(1)', 'data:text/html,test', 'file:///tmp/example', '//example.com']) {
    assert.equal(webLinkHref(value), '')
  }
})
