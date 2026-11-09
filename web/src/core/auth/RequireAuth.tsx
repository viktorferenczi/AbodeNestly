import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './AuthProvider.tsx';

// Renders nothing while loading: DESIGN A1 forbids spinners over content.
export function RequireAuth() {
  const { status } = useAuth();
  const { pathname, search } = useLocation();
  if (status === 'loading') return null;
  if (status === 'signedOut') {
    return <Navigate to={'/signin?next=' + encodeURIComponent(pathname + search)} replace />;
  }
  return <Outlet />;
}
