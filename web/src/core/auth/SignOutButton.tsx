import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from './AuthProvider.tsx';

export function SignOutButton() {
  const { client } = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);

  async function onClick() {
    setFailed(false);
    try {
      // Wait for the session to clear: navigating first would let SignIn bounce back through Landing.
      await client.signOut();
    } catch {
      setFailed(true);
      return;
    }
    // Replaces the ?next=/profile that RequireAuth attaches when the session drops.
    void navigate('/signin', { replace: true });
  }

  return (
    <>
      <button type="button" onClick={() => void onClick()}>
        Sign out
      </button>
      {failed ? <p role="alert">Couldn't sign out. Try again.</p> : null}
    </>
  );
}
