import { useEffect, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router';
import { useAuth } from './AuthProvider.tsx';
import { landingPath, pickHousehold } from './landing.ts';
import { readLastHousehold, writeLastHousehold } from './lastHousehold.ts';

// Shared by `/` and `/auth/callback`, so magic link, Apple and Google land the same way.
export function Landing() {
  const { status, session, client } = useAuth();
  const [params] = useSearchParams();
  const next = params.get('next');
  const userId = session?.user.id;
  const [target, setTarget] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!userId) return;
    let active = true;
    void client.fetchMemberships(userId).then((result) => {
      if (!active) return;
      if (!result.ok) {
        // Offline, an existing session stays usable (SPEC §6.5): enter the shell at Home.
        // Online, a failure is a real problem and stays a blocking alert.
        if (!navigator.onLine) return setTarget('/home');
        return setFailed(true);
      }
      const lastId = readLastHousehold();
      const chosen = pickHousehold(result.value, lastId);
      if (chosen) writeLastHousehold(chosen.household_id);
      const path = landingPath({ memberships: result.value, lastHouseholdId: lastId, next });
      setTarget(path);
    });
    return () => {
      active = false;
    };
  }, [client, userId, next]);

  if (status === 'signedOut') return <Navigate to="/signin" replace />;
  if (target) return <Navigate to={target} replace />;
  if (failed) return <p role="alert">Couldn't load your households. Reload to try again.</p>;
  return null;
}
