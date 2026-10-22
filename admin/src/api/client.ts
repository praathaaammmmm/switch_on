/** A failed admin API call, with the server's stable error code and a message safe to show. */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

type Query = Record<string, string | number | boolean | undefined | null>

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  query?: Query
}

export const API_BASE = '/v1/admin'

export function queryString(query?: Query): string {
  if (!query) return ''
  const params = new URLSearchParams()
  Object.entries(query).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '' && v !== false) params.set(k, String(v))
  })
  const s = params.toString()
  return s ? `?${s}` : ''
}

/**
 * Calls the admin API on the same origin. The session is an HttpOnly cookie the browser sends itself; every request
 * carries the dashboard header the server requires on changes (a cross-site page can't add it).
 */
export async function api<T>(path: string, { method = 'GET', body, query }: RequestOptions = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(API_BASE + path + queryString(query), {
      method,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'switchon-admin' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'network', "Can't reach the Switch On API. Check that the backend is running.")
  }
  if (res.status === 204) return undefined as T
  const data = await res.json().catch(() => null)
  if (!res.ok) {
    const err = data?.error
    throw new ApiError(res.status, err?.code ?? 'error', err?.message ?? `Request failed (${res.status}).`)
  }
  return data as T
}

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.')
