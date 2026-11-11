import { createContext, useContext, type ReactNode } from 'react';
import type { ApiClient } from './apiClient.ts';

const ApiContext = createContext<ApiClient | null>(null);

export function ApiProvider({ client, children }: { client: ApiClient; children: ReactNode }) {
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const value = useContext(ApiContext);
  if (!value) throw new Error('useApi must be used inside <ApiProvider>');
  return value;
}
