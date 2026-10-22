import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from './api/client'
import { ME_KEY } from './auth/AuthContext'

export function createQueryClient() {
  // A 401 from any call means the session ended (expired, revoked or deactivated): drop to the sign-in screen.
  const onError = (e: unknown) => { if (e instanceof ApiError && e.status === 401) client.setQueryData(ME_KEY, null) }
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: { retry: (n, e) => !(e instanceof ApiError && e.status < 500) && n < 2, refetchOnWindowFocus: true, staleTime: 15_000 },
    },
  })
  return client
}
