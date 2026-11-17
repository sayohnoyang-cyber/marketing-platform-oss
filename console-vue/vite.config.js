import { fileURLToPath, URL } from 'node:url'
import { networkInterfaces } from 'node:os'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command, mode }) => {
  const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env }
  const adapters = networkInterfaces()
  const lan = Object.entries(adapters)
    .filter(([name]) => /^(en\d+|eth\d+|wlan\d+|Wi-Fi|Ethernet)$/i.test(name))
    .flatMap(([, addresses]) => addresses || [])
    .find(address => address.family === 'IPv4' && !address.internal)
  const apiOrigin = env.VITE_API_ORIGIN || 'http://127.0.0.1:9100'
  const devHost = env.VITE_DEV_HOST || '0.0.0.0'
  const api = new URL(apiOrigin)
  const localApi = ['127.0.0.1', 'localhost', '[::1]'].includes(api.hostname)
  const publicOrigin = env.VITE_PUBLIC_SHORTLINK_ORIGIN || (command === 'serve' && localApi && lan && devHost === '0.0.0.0' ? `${api.protocol}//${lan.address}${api.port ? `:${api.port}` : ''}` : '')
  return {
    define: { 'import.meta.env.VITE_PUBLIC_SHORTLINK_ORIGIN': JSON.stringify(publicOrigin) },
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: devHost,
      port: 5173,
      proxy: {
        '/api': {
          target: apiOrigin,
          changeOrigin: true,
          configure(proxy) {
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.removeHeader('origin')
            })
          }
        }
      }
    }
  }
})
