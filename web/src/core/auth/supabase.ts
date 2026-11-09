import { createClient } from '@supabase/supabase-js';

// Persistence and refresh come from the SDK (CORE-01 AC6). Keys come from env, never literals.
export const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL,
  import.meta.env.VITE_SUPABASE_ANON_KEY,
  { auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true, flowType: 'pkce' } },
);
