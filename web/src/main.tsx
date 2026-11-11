import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';
import { createAppRouter } from './app/router.tsx';
import { createHttpApiClient } from './core/api/apiClient.ts';
import { ApiProvider } from './core/api/ApiProvider.tsx';
import { createSupabaseAuthClient } from './core/auth/authClient.ts';
import { AuthProvider } from './core/auth/AuthProvider.tsx';
import { supabase } from './core/auth/supabase.ts';
import './ui/tokens.css';

const client = createSupabaseAuthClient(supabase);
const api = createHttpApiClient(import.meta.env.VITE_API_BASE_URL, supabase);
const router = createAppRouter();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider client={client}>
      <ApiProvider client={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>
  </StrictMode>,
);
