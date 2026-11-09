import { createBrowserRouter, Navigate } from 'react-router';
import { Landing } from '../core/auth/Landing.tsx';
import { RequireAuth } from '../core/auth/RequireAuth.tsx';
import { SignIn } from '../core/auth/SignIn.tsx';
import { SignOutButton } from '../core/auth/SignOutButton.tsx';
import { Placeholder } from './Placeholder.tsx';
import { Shell } from './Shell.tsx';

export function createAppRouter() {
  return createBrowserRouter([
    { path: '/signin', element: <SignIn /> },
    { path: '/auth/callback', element: <Landing /> },
    {
      element: <RequireAuth />,
      children: [
        {
          element: <Shell />,
          children: [
            { path: '/', element: <Landing /> },
            { path: '/welcome', element: <Placeholder /> },
            { path: '/home', element: <Placeholder /> },
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
