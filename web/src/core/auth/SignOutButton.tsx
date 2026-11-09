import { useNavigate } from 'react-router';
import { useAuth } from './AuthProvider.tsx';

export function SignOutButton() {
  const { client } = useAuth();
  const navigate = useNavigate();
  async function onClick() {
    // Navigate first: once the session clears, RequireAuth would redirect with ?next=/profile.
    void navigate('/signin', { replace: true });
    await client.signOut();
  }
  return (
    <button type="button" onClick={() => void onClick()}>
      Sign out
    </button>
  );
}
