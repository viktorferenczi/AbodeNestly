import { useEffect, useReducer, useState, type FormEvent } from 'react';
import { Navigate, useSearchParams } from 'react-router';
import { OfflineBanner } from '../../ui/OfflineBanner.tsx';
import { useOnline } from '../../ui/useOnline.ts';
import type { AuthError, OAuthProvider } from './authClient.ts';
import { useAuth } from './AuthProvider.tsx';
import { safeNext } from './landing.ts';
import styles from './SignIn.module.css';

const RESEND_SECONDS = 30;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const MESSAGES: Record<AuthError['code'], string> = {
  invalid_email: 'Enter a valid email address.',
  rate_limited: 'Too many attempts. Wait a minute and try again.',
  network: "Couldn't reach the server. Check your connection and try again.",
  unknown: 'Something went wrong. Try again.',
};

type State =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'link-sent'; secondsLeft: number; resending: boolean; error: AuthError['code'] | null }
  | { kind: 'error'; code: AuthError['code'] };

type Action =
  | { type: 'submit' }
  | { type: 'sent' }
  | { type: 'failed'; code: AuthError['code'] }
  | { type: 'resend' }
  | { type: 'resent' }
  | { type: 'resend-failed'; code: AuthError['code'] }
  | { type: 'tick' };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'submit':
      return { kind: 'sending' };
    case 'sent':
      return { kind: 'link-sent', secondsLeft: RESEND_SECONDS, resending: false, error: null };
    case 'failed':
      return { kind: 'error', code: action.code };
    case 'resend':
      return state.kind === 'link-sent' ? { ...state, resending: true, error: null } : state;
    case 'resent':
      return { kind: 'link-sent', secondsLeft: RESEND_SECONDS, resending: false, error: null };
    case 'resend-failed':
      return state.kind === 'link-sent' ? { ...state, resending: false, error: action.code } : state;
    case 'tick':
      return state.kind === 'link-sent' && state.secondsLeft > 0
        ? { ...state, secondsLeft: state.secondsLeft - 1 }
        : state;
  }
}

export function SignIn() {
  const { status, client } = useAuth();
  const online = useOnline();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [state, dispatch] = useReducer(reducer, { kind: 'idle' });

  const counting = state.kind === 'link-sent' && state.secondsLeft > 0;
  useEffect(() => {
    if (!counting) return;
    const timer = setInterval(() => dispatch({ type: 'tick' }), 1000);
    return () => clearInterval(timer); // cleared on unmount too
  }, [counting]);

  const next = safeNext(params.get('next'));
  const redirectTo =
    `${window.location.origin}/auth/callback` + (next ? `?next=${encodeURIComponent(next)}` : '');

  if (status === 'signedIn') {
    return <Navigate to={'/auth/callback' + (next ? `?next=${encodeURIComponent(next)}` : '')} replace />;
  }

  const busy = state.kind === 'sending';
  const disabled = !online || busy;

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (disabled) return;
    const trimmed = email.trim();
    if (!EMAIL_PATTERN.test(trimmed)) return dispatch({ type: 'failed', code: 'invalid_email' });
    dispatch({ type: 'submit' });
    const error = await client.signInWithOtp(trimmed, redirectTo);
    dispatch(error ? { type: 'failed', code: error.code } : { type: 'sent' });
  }

  async function onResend() {
    dispatch({ type: 'resend' });
    const error = await client.signInWithOtp(email.trim(), redirectTo);
    dispatch(error ? { type: 'resend-failed', code: error.code } : { type: 'resent' });
  }

  async function onProvider(provider: OAuthProvider) {
    dispatch({ type: 'submit' });
    const error = await client.signInWithOAuth(provider, redirectTo);
    // On success the browser leaves for the provider, so only failures return here.
    if (error) dispatch({ type: 'failed', code: error.code });
  }

  const fieldError = state.kind === 'error' ? MESSAGES[state.code] : null;

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <p className={styles.wordmark}>AbodeNestly</p>
        <h1 className={styles.heading}>Sign in to AbodeNestly</h1>
        {!online ? <OfflineBanner message="You're offline. Connect to sign in." /> : null}

        {state.kind === 'link-sent' ? (
          <section aria-labelledby="link-sent-title" className={styles.sent}>
            <h2 id="link-sent-title" className={styles.sentTitle}>
              Check your email
            </h2>
            <p className={styles.muted}>We sent a sign-in link to {email.trim()}.</p>
            <button
              type="button"
              className={styles.secondary}
              disabled={!online || state.resending || state.secondsLeft > 0}
              onClick={() => void onResend()}
            >
              {state.secondsLeft > 0 ? `Resend (${state.secondsLeft} s)` : 'Resend'}
            </button>
            {state.error ? (
              <p role="alert" className={styles.error}>
                {MESSAGES[state.error]}
              </p>
            ) : null}
          </section>
        ) : (
          <form onSubmit={(e) => void onSubmit(e)} noValidate className={styles.form}>
            <label htmlFor="email" className={styles.label}>
              Email
            </label>
            <input
              id="email"
              name="email"
              type="email"
              inputMode="email"
              autoComplete="email"
              className={styles.input}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={fieldError ? 'email-error' : undefined}
            />
            {fieldError ? (
              <p id="email-error" role="alert" className={styles.error}>
                {fieldError}
              </p>
            ) : null}
            <button type="submit" className={styles.primary} disabled={disabled} aria-busy={busy}>
              {busy ? <span className={styles.spinner} aria-hidden="true" /> : null}
              Send magic link
            </button>
          </form>
        )}

        {state.kind === 'link-sent' ? null : (
          <>
          <div className={styles.divider} role="separator">
            <span>or</span>
          </div>
          <button type="button" className={styles.secondary} disabled={disabled} onClick={() => void onProvider('apple')}>
            Sign in with Apple
          </button>
          <button type="button" className={styles.secondary} disabled={disabled} onClick={() => void onProvider('google')}>
            Sign in with Google
          </button>
          </>
        )}
        <p className={styles.legal}>By continuing you agree to the Terms of Service and Privacy Policy.</p>
      </div>
    </main>
  );
}
