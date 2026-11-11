import type { ReactNode } from 'react';

// Stand-in until the module stories and CORE-06 (profile) replace each route.
export function Placeholder({ children }: { children?: ReactNode }) {
  return (
    <div style={{ padding: 16 }}>
      <p>Coming soon</p>
      {children}
    </div>
  );
}
