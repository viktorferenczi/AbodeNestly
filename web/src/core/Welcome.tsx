import { useReducer, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { OfflineBanner } from '../ui/OfflineBanner.tsx';
import { useOnline } from '../ui/useOnline.ts';
import { useApi } from './api/ApiProvider.tsx';
import type { ApiFailure } from './api/apiClient.ts';
import { useAuth } from './auth/AuthProvider.tsx';
import { writeLastHousehold } from './auth/lastHousehold.ts';
import { SignOutButton } from './auth/SignOutButton.tsx';
import { householdHomePath, welcomeReducer } from './Welcome.ts';
import styles from './Welcome.module.css';

const CREATE_MESSAGES: Record<ApiFailure['code'], string> = {
  validation: 'Enter a name for your household.',
  invalid_code: 'Something went wrong. Try again.',
  network: "Couldn't reach the server. Check your connection and try again.",
  unknown: 'Something went wrong. Try again.',
};

const JOIN_MESSAGES: Record<ApiFailure['code'], string> = {
  validation: 'Enter your invite code.',
  invalid_code: "That code isn't valid or has expired. Check it and try again.",
  network: "Couldn't reach the server. Check your connection and try again.",
  unknown: 'Something went wrong. Try again.',
};

export function Welcome() {
  const { profile } = useAuth();
  const api = useApi();
  const online = useOnline();
  const navigate = useNavigate();
  const [state, dispatch] = useReducer(welcomeReducer, { kind: 'idle' });
  // Kept outside the reducer so typed values survive every error state (AC3).
  const [name, setName] = useState('');
  const [code, setCode] = useState('');

  const creating = state.kind === 'creating';
  const joining = state.kind === 'joining';
  const disabled = !online || creating || joining;

  const createError =
    state.kind === 'create-error' ? CREATE_MESSAGES[state.code] : null;
  const joinError =
    state.kind === 'error-invalid-code'
      ? JOIN_MESSAGES.invalid_code
      : state.kind === 'join-error'
        ? JOIN_MESSAGES[state.code]
        : null;

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    if (disabled) return;
    const trimmed = name.trim();
    if (!trimmed) return dispatch({ type: 'create-failed', code: 'validation' });
    dispatch({ type: 'create' });
    const result = await api.createHousehold(trimmed);
    if (!result.ok) return dispatch({ type: 'create-failed', code: result.error.code });
    // Remember it so the next `/` load lands on this household.
    writeLastHousehold(result.value.id);
    void navigate(householdHomePath(), { replace: true });
  }

  async function onJoin(event: FormEvent) {
    event.preventDefault();
    if (disabled) return;
    const trimmed = code.trim();
    if (!trimmed) return dispatch({ type: 'join-failed', code: 'validation' });
    dispatch({ type: 'join' });
    const result = await api.acceptInvite(trimmed);
    if (!result.ok) return dispatch({ type: 'join-failed', code: result.error.code });
    writeLastHousehold(result.value.household_id);
    void navigate(householdHomePath(), { replace: true });
  }

  const displayName = profile?.display_name;

  return (
    <main className={styles.page}>
      <h1 className={styles.greeting}>{displayName ? `Welcome, ${displayName}` : 'Welcome'}</h1>
      {!online ? <OfflineBanner message="You're offline. Connect to create or join a household." /> : null}

      <form onSubmit={(e) => void onCreate(e)} noValidate className={styles.card} aria-labelledby="create-title">
        <h2 id="create-title" className={styles.cardTitle}>
          Create a household
        </h2>
        <label htmlFor="household-name" className={styles.label}>
          Household name
        </label>
        <input
          id="household-name"
          name="household-name"
          type="text"
          autoComplete="off"
          className={styles.input}
          value={name}
          onChange={(e) => setName(e.target.value)}
          aria-invalid={createError ? true : undefined}
          aria-describedby={createError ? 'create-error' : undefined}
        />
        {createError ? (
          <p id="create-error" role="alert" className={styles.error}>
            {createError}
          </p>
        ) : null}
        <button type="submit" className={styles.primary} disabled={disabled} aria-busy={creating}>
          {creating ? <span className={styles.spinner} aria-hidden="true" /> : null}
          Create household
        </button>
      </form>

      <form onSubmit={(e) => void onJoin(e)} noValidate className={styles.card} aria-labelledby="join-title">
        <h2 id="join-title" className={styles.cardTitle}>
          Join with a code
        </h2>
        <label htmlFor="invite-code" className={styles.label}>
          Invite code
        </label>
        <input
          id="invite-code"
          name="invite-code"
          type="text"
          autoComplete="off"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          className={styles.input}
          value={code}
          onChange={(e) => setCode(e.target.value)}
          aria-invalid={joinError ? true : undefined}
          aria-describedby={joinError ? 'join-error' : undefined}
        />
        {joinError ? (
          <p id="join-error" role="alert" className={styles.error}>
            {joinError}
          </p>
        ) : null}
        <button type="submit" className={styles.primary} disabled={disabled} aria-busy={joining}>
          {joining ? <span className={styles.spinner} aria-hidden="true" /> : null}
          Join household
        </button>
      </form>

      <div className={styles.footer}>
        <SignOutButton />
      </div>
    </main>
  );
}
