import { createBrowserRouter, Navigate, useParams } from 'react-router';
import { HomeFeed } from '../core/HomeFeed.tsx';
import { joinPath } from '../core/InviteSheet.ts';
import { HouseholdSettings } from '../core/HouseholdSettings.tsx';
import { Profile } from '../core/Profile.tsx';
import { RecentlyDeleted } from '../core/RecentlyDeleted.tsx';
import { Search } from '../core/Search.tsx';
import { Members } from '../core/Members.tsx';
import { Welcome } from '../core/Welcome.tsx';
import { Landing } from '../core/auth/Landing.tsx';
import { RequireAuth } from '../core/auth/RequireAuth.tsx';
import { SignIn } from '../core/auth/SignIn.tsx';
import { Placeholder } from './Placeholder.tsx';
import { Shell } from './Shell.tsx';

// An invite link opens the join card with the code filled in; joining still needs a tap.
export function JoinRedirect() {
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
              element: <HomeFeed />,
            },
            { path: '/household', element: <HouseholdSettings /> },
            { path: '/household/members', element: <Members /> },
            { path: '/shopping', element: <Placeholder /> },
            { path: '/pantry', element: <Placeholder /> },
            { path: '/maintenance', element: <Placeholder /> },
            { path: '/search', element: <Search /> },
            { path: '/deleted', element: <RecentlyDeleted /> },
            // Entity destinations of search rows (DESIGN B8); the module list stories replace these.
            ...[
              '/shopping/:listId',
              '/shopping/stores/:id',
              '/pantry/settings',
              '/pantry/recipes/:id',
              '/maintenance/assets/:id',
              '/maintenance/tasks/:id',
              '/maintenance/contractors',
            ].map((path) => ({ path, element: <Placeholder /> })),
            { path: '/profile', element: <Profile /> },
            // B9 arrives with CORE-12; the link must not bounce to / before then.
            { path: '/profile/notifications', element: <Placeholder /> },
          ],
        },
      ],
    },
    { path: '*', element: <Navigate to="/" replace /> },
  ]);
}
