import { createBrowserRouter, Navigate, useParams } from 'react-router';
import { HomeFeed } from '../core/HomeFeed.tsx';
import { joinPath } from '../core/InviteSheet.ts';
import { HouseholdSettings } from '../core/HouseholdSettings.tsx';
import { NotificationPrefs } from '../core/NotificationPrefs.tsx';
import { Profile } from '../core/Profile.tsx';
import { RecentlyDeleted } from '../core/RecentlyDeleted.tsx';
import { Search } from '../core/Search.tsx';
import { Members } from '../core/Members.tsx';
import { Welcome } from '../core/Welcome.tsx';
import { Landing } from '../core/auth/Landing.tsx';
import { RequireAuth } from '../core/auth/RequireAuth.tsx';
import { SignIn } from '../core/auth/SignIn.tsx';
import { ListDetail, Lists, Stores } from '../shopping/index.ts';
import { Placeholder } from './Placeholder.tsx';
import { RequireModule } from './RequireModule.tsx';
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
            {
              element: <RequireModule module="shopping" />,
              children: [
                { path: '/shopping', element: <Lists /> },
                { path: '/shopping/stores', element: <Stores /> },
                { path: '/shopping/stores/:id', element: <Placeholder /> },
                { path: '/shopping/:listId', element: <ListDetail /> },
              ],
            },
            { path: '/pantry', element: <Placeholder /> },
            { path: '/maintenance', element: <Placeholder /> },
            { path: '/search', element: <Search /> },
            { path: '/deleted', element: <RecentlyDeleted /> },
            // Entity destinations of search rows (DESIGN B8); the module list stories replace these.
            ...[
              '/pantry/settings',
              '/pantry/recipes/:id',
              '/maintenance/assets/:id',
              '/maintenance/tasks/:id',
              '/maintenance/contractors',
            ].map((path) => ({ path, element: <Placeholder /> })),
            { path: '/profile', element: <Profile /> },
            { path: '/profile/notifications', element: <NotificationPrefs /> },
          ],
        },
      ],
    },
    { path: '*', element: <Navigate to="/" replace /> },
  ]);
}
