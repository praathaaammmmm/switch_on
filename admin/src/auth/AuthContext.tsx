import { createContext, useContext, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { AdminMe, PermissionCode } from '../api/types'

interface Auth {
  me: AdminMe | null
  loading: boolean
  can: (p: PermissionCode) => boolean
}

const AuthContext = createContext<Auth>({ me: null, loading: true, can: () => false })

export const ME_KEY = ['me'] as const

/** Who is signed in. A 401 means "not signed in", not an error. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const query = useQuery({
    queryKey: ME_KEY,
    queryFn: async () => {
      try {
        return await api<AdminMe>('/me')
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    staleTime: 60_000,
    retry: false,
  })
  const me = query.data ?? null
  return (
    <AuthContext.Provider value={{ me, loading: query.isPending, can: (p) => !!me?.permissions.includes(p) }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => useContext(AuthContext)
export const useCan = () => useAuth().can

/** Renders [children] only for roles with [permission]; the server enforces the same rule. */
export function Can({ permission, children, fallback = null }: { permission: PermissionCode; children: ReactNode; fallback?: ReactNode }) {
  return useCan()(permission) ? <>{children}</> : <>{fallback}</>
}
