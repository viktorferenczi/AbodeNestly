import type { Household } from '@abodenestly/contract';
import { useReducer, useRef, useState, type KeyboardEvent } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { OfflineBanner } from '../ui/OfflineBanner.tsx';
import { Sheet } from '../ui/Sheet.tsx';
import { useOnline } from '../ui/useOnline.ts';
import type { ApiResult } from './api/apiClient.ts';
import { useApi } from './api/ApiProvider.tsx';
import { useAuth } from './auth/AuthProvider.tsx';
import { clearLastHousehold } from './auth/lastHousehold.ts';
import { clearCachedHousehold, useHousehold } from './household/HouseholdProvider.tsx';
import {
  currencyOptions,
  settingsMode,
  settingsReducer,
  withModule,
  type ModuleKey,
} from './HouseholdSettings.ts';
import styles from './HouseholdSettings.module.css';

const MODULES: Array<{ key: ModuleKey; label: string; description: string }> = [
  { key: 'shopping', label: 'Shopping', description: 'Shared shopping lists that sync across the household.' },
  { key: 'pantry', label: 'Pantry', description: 'What you have at home, with expiry dates and low-stock alerts.' },
  { key: 'maintenance', label: 'Maintenance', description: 'Recurring home tasks, warranties and service history.' },
];

function saveError(code: string): string {
  if (code === 'forbidden') return 'Only owners can change household settings.';
  if (code === 'network') return "Couldn't save. Check your connection and try again.";
  return "Couldn't save. Try again.";
}

// DESIGN B5. Delete household is deferred until SPEC defines an endpoint and its semantics.
export function HouseholdSettings() {
  const api = useApi();
  const online = useOnline();
  const navigate = useNavigate();
  const { session } = useAuth();
  const { status, household, role, replace, reload } = useHousehold();
  const [state, dispatch] = useReducer(settingsReducer, { kind: 'idle' });
  const [optimistic, setOptimistic] = useState<Household | null>(null);
  const [nameDraft, setNameDraft] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  // A ref, not state: Enter then the blur it causes must not save twice before React re-renders.
  const busy = useRef(false);

  if (status === 'none') return <Navigate to="/" replace />;
  if (status === 'loading' || !household) return <p className={styles.note}>Loading…</p>;

  const mode = settingsMode(role, online);
  const saving = state.kind === 'saving';
  const locked = mode !== 'owner' || saving;
  const shown = optimistic ?? household;
  const name = nameDraft ?? shown.name;

  async function save(run: () => Promise<ApiResult<Household>>, display?: Household) {
    if (busy.current) return;
    busy.current = true;
    dispatch({ type: 'save' });
    setOptimistic(display ?? null);
    const result = await run();
    busy.current = false;
    setOptimistic(null);
    if (result.ok) {
      setNameDraft(null);
      replace(result.value);
      return dispatch({ type: 'saved' });
    }
    dispatch({ type: 'failed', code: result.error.code });
    // The role may have changed under us; reload so the screen reflects the truth.
    if (result.error.code === 'forbidden') reload();
  }

  function onToggle(key: ModuleKey, on: boolean) {
    const { body, display } = withModule(household!, key, on);
    void save(() => api.updateModules(household!.id, body), display);
  }

  function commitName() {
    const trimmed = name.trim();
    if (mode !== 'owner' || !trimmed || trimmed === household!.name) return;
    void save(() => api.updateHousehold(household!.id, { name: trimmed }));
  }

  function onNameKey(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') commitName();
  }

  async function onLeave() {
    const userId = session?.user.id;
    if (!userId) return;
    dispatch({ type: 'leave' });
    const result = await api.removeMember(household!.id, userId);
    if (!result.ok) return dispatch({ type: 'leave-failed', code: result.error.code });
    clearLastHousehold();
    clearCachedHousehold(userId, household!.id);
    // Landing sends the user to their next household, or to /welcome when there is none (CORE-07 adds a switcher).
    void navigate('/', { replace: true });
  }

  const leaving = state.kind === 'leaving';
  const sheetError = state.kind === 'error' && confirming ? state : null;

  return (
    <div className={styles.page}>
      {mode === 'offline' ? <OfflineBanner message="You're offline. Connect to change household settings." /> : null}

      <label className={styles.field}>
        <span className={styles.label}>Household name</span>
        <input
          className={styles.input}
          value={name}
          readOnly={mode !== 'owner'}
          disabled={saving}
          onChange={(e) => setNameDraft(e.target.value)}
          onBlur={commitName}
          onKeyDown={onNameKey}
        />
      </label>

      <section aria-labelledby="modules-heading" className={styles.section}>
        <h2 id="modules-heading" className={styles.heading}>
          Modules
        </h2>
        {MODULES.map(({ key, label, description }) => (
          // The whole row is the label, so its full width is the 48px target (DESIGN A5); the accessible
          // name stays just the module name.
          <label key={key} className={styles.switchRow}>
            <span className={styles.switchText}>
              <span id={`module-${key}-label`} className={styles.switchLabel}>
                {label}
              </span>
              <span id={`module-${key}-desc`} className={styles.note}>
                {description}
              </span>
            </span>
            <input
              type="checkbox"
              role="switch"
              className={styles.switch}
              aria-labelledby={`module-${key}-label`}
              aria-describedby={`module-${key}-desc`}
              checked={shown[`module_${key}`]}
              disabled={locked}
              onChange={(e) => onToggle(key, e.target.checked)}
            />
          </label>
        ))}
      </section>

      <label className={styles.field}>
        <span className={styles.label}>Currency</span>
        <select
          className={styles.input}
          value={shown.currency}
          disabled={locked}
          onChange={(e) => void save(() => api.updateHousehold(household.id, { currency: e.target.value }), { ...household, currency: e.target.value })}
        >
          {currencyOptions(shown.currency).map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </label>

      <p aria-live="polite" className={styles.note}>
        {saving ? 'Saving…' : ''}
      </p>
      {state.kind === 'error' && !confirming ? (
        <p role="alert" className={styles.error}>
          {saveError(state.code)}
        </p>
      ) : null}

      {role ? (
        <button
          type="button"
          className={styles.danger}
          disabled={mode === 'offline' || saving}
          onClick={() => {
            dispatch({ type: 'reset' });
            setConfirming(true);
          }}
        >
          Leave household
        </button>
      ) : null}

      {confirming ? (
        <Sheet
          title={`Leave ${household.name}?`}
          onClose={() => {
            if (leaving) return;
            dispatch({ type: 'reset' });
            setConfirming(false);
          }}
          footer={
            <div className={styles.actions}>
              <button type="button" className={styles.secondary} disabled={leaving} onClick={() => {
                dispatch({ type: 'reset' });
                setConfirming(false);
              }}>
                Cancel
              </button>
              <button type="button" className={styles.danger} disabled={leaving || !online} aria-busy={leaving} onClick={() => void onLeave()}>
                Leave
              </button>
            </div>
          }
        >
          <p className={styles.note}>You'll lose access to this household's lists until someone invites you back.</p>
          {sheetError ? (
            <p role="alert" className={styles.error}>
              {sheetError.code === 'last_owner'
                ? "You're the last owner. Make someone else an owner before leaving."
                : "Couldn't leave the household. Try again."}
            </p>
          ) : null}
        </Sheet>
      ) : null}
    </div>
  );
}
