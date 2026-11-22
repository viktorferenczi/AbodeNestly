import { Navigate, Outlet } from 'react-router';
import { useHousehold } from '../core/household/HouseholdProvider.tsx';

/** Routes of a module whose flag is off redirect to `/` (react-web skill: Module gating). */
export function RequireModule({ module }: { module: 'shopping' | 'pantry' | 'maintenance' }) {
  const { status, modules } = useHousehold();
  if (status === 'loading') return null;
  return modules[module] ? <Outlet /> : <Navigate to="/" replace />;
}
