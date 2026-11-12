import { createBrowserRouter, Link, Navigate, useParams } from 'react-router';
import { joinPath } from '../core/InviteSheet.ts';
import { Members } from '../core/Members.tsx';
import { Welcome } from '../core/Welcome.tsx';
import { Landing } from '../core/auth/Landing.tsx';
import { RequireAuth } from '../core/auth/RequireAuth.tsx';
import { SignIn } from '../core/auth/SignIn.tsx';
import { SignOutButton } from '../core/auth/SignOutButton.tsx';
import { Placeholder } from './Placeholder.tsx';
import { Shell } from './Shell.tsx';

// An invite link opens the join card with the code filled in; joining still needs a tap.
function JoinRedirect() {
  const { code = '' } = useParams();
  return <Navigate to={joinPath(code)} replace />;
}

export function createAppRouter() {
  return createBrowserRouter([
    { path: '/signin', element: <SignIn /> },
    { path: '/auth/callback', element: <Landing /> },
    {
      element: <RequireAuth />,
      children: [
        // Signed-out visitors are sent to /signin?next=/join/<code> by RequireAuth.
        { path: '/join/:code', element: <JoinRedirect /> },
        {
          element: <Shell />,
          children: [
            { path: '/', element: <Landing /> },
            { path: '/welcome', element: <Welcome /> },
            {
              path: '/home',
              element: (
                <Placeholder>
                  <Link to="/household/members">Members</Link>
                </Placeholder>
              ),
            },
            { path: '/household/members', element: <Members /> },
            { path: '/shopping', element: <Placeholder /> },
            { path: '/pantry', element: <Placeholder /> },
            { path: '/maintenance', element: <Placeholder /> },
            {
              path: '/profile',
              element: (
                <Placeholder>
                  <SignOutButton />
                </Placeholder>
              ),
            },
          ],
        },
      ],
    },
    { path: '*', element: <Navigate to="/" replace /> },
  ]);
}
