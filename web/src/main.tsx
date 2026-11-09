import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router';
import { createAppRouter } from './app/router.tsx';
import { createSupabaseAuthClient } from './core/auth/authClient.ts';
import { AuthProvider } from './core/auth/AuthProvider.tsx';
import { supabase } from './core/auth/supabase.ts';
import './ui/tokens.css';

const client = createSupabaseAuthClient(supabase);
const router = createAppRouter();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider client={client}>
      <RouterProvider router={router} />
    </AuthProvider>
  </StrictMode>,
);
