import { useState } from 'react';
import { Navigate } from 'react-router';
import { readLastHousehold } from './auth/lastHousehold.ts';
import { InviteSheet } from './InviteSheet.tsx';
import styles from './Members.module.css';

// DESIGN B6. Member rows (avatar, role, overflow menu) arrive with CORE-04 and render above the button.
export function Members() {
  const householdId = readLastHousehold();
  const [inviting, setInviting] = useState(false);
  if (!householdId) return <Navigate to="/" replace />;
  return (
    <>
      {/* The Shell's TopBar owns the h1. */}
      <div className={styles.heading}>
        <h2 className={styles.title}>Members</h2>
        <button type="button" className={styles.invite} onClick={() => setInviting(true)}>
          Invite
        </button>
      </div>
      {inviting ? <InviteSheet householdId={householdId} onClose={() => setInviting(false)} /> : null}
    </>
  );
}
