import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import type { ProxyOptions } from 'vite'

/**
 * CORS in this backend is restrictive: auth-service and payment-service only allow
 * localhost:3000, and notification-service has no CORS configuration at all. A dev
 * server on :5173 is therefore rejected with 403 "Invalid CORS request".
 *
 * Proxying through Vite is not sufficient on its own: http-proxy forwards the
 * browser's `Origin: http://localhost:5173` header verbatim, and Spring's CorsFilter
 * rejects that before the request is ever handled.
 *
 * Rewriting Origin to an allow-listed origin makes the backend treat the call as a
 * permitted cross-origin request. The browser is unaffected because it sees the
 * response as same-origin via the proxy.
 *
 * NOTE: this only fixes local development. A production build served from its own
 * origin needs that origin added to the allowed origins in the backend security
 * configs (and a CORS config added to notification-service, which has none).
 *
 * The three services and Mailpit occupy disjoint path prefixes, so a single
 * prefix-based proxy table routes all four without any ordering ambiguity.
 */
const ALLOWED_ORIGIN = 'http://localhost:3000'

/**
 * http-proxy applies `headers` to the outgoing request. Overriding the header here is
 * more dependable than mutating it in a `proxyReq` listener, which does not fire
 * reliably for every proxied request.
 */
const corsCompatHeaders: Record<string, string> = {
  origin: ALLOWED_ORIGIN,
  referer: `${ALLOWED_ORIGIN}/`,
}

function backend(target: string, extra: ProxyOptions = {}): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    headers: corsCompatHeaders,
    ...extra,
  }
}

/**
 * Mailpit is not a Spring service, and it answers 403 to any request carrying an
 * `Origin` header at all — verified directly, with and without a Referer. So the CORS
 * compatibility headers above have to be taken back off rather than rewritten: there
 * is nothing for Mailpit to allow, and the browser still sees the response as
 * same-origin through the proxy.
 *
 * `configure` is the only reliable place to do this. Setting `headers: { origin: '' }`
 * still sends an empty Origin, which Mailpit also rejects with 403, and a `proxyReq`
 * handler passed inline is applied before the configured headers are merged.
 */
function mailApi(target: string): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/mailpit/, ''),
    configure: (proxyServer) => {
      proxyServer.on('proxyReq', (proxyRequest) => {
        proxyRequest.removeHeader('origin')
      })
    },
  }
}

const proxy: Record<string, ProxyOptions> = {
  // auth-service: /api/auth/*, /api/users/*
  '/api': backend('http://localhost:8081'),
  // payment-service: /payments* and /wallets* (no /api prefix)
  '/payments': backend('http://localhost:8082'),
  '/wallets': backend('http://localhost:8082'),
  // notification-service: /notify/*
  '/notify': backend('http://localhost:8083'),
  // Mailpit's mail API, where delivered notification emails actually land.
  '/mailpit': mailApi('http://localhost:8025'),
}

export default defineConfig({
  plugins: [react()],
  base: '/',
  server: {
    open: true,
    port: 5173,
    // Vite 5 also enforces its own dev-server CORS; allow any localhost port so the
    // proxy upstream headers are the only thing that matter.
    cors: { origin: true },
    proxy,
  },
  preview: {
    port: 4173,
    cors: { origin: true },
    proxy,
  },
})