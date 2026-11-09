import { useNavigate } from 'react-router';
import { useAuth } from './AuthProvider.tsx';

export function SignOutButton() {
  const { client } = useAuth();
  const navigate = useNavigate();
  async function onClick() {
    await client.signOut();
    void navigate('/signin', { replace: true });
  }
  return (
    <button type="button" onClick={() => void onClick()}>
      Sign out
    </button>
  );
}
