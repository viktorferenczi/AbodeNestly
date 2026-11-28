import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { useApi } from '../../core/api/ApiProvider.tsx';
import { formatQty } from '../ItemSheet.ts';
import { mapPublicList, type PublicList } from './mapPublicList.ts';
import styles from './SharedList.module.css';

// Same shape as the API's own check (api/_lib/shares.ts): anything else is dead without a request.
const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;

type State = { kind: 'loading' } | { kind: 'ready'; list: PublicList } | { kind: 'expired' } | { kind: 'error' };

// DESIGN C9: public and read-only. No session, no local database, no controls.
export function SharedList() {
  const { token = '' } = useParams();
  const api = useApi();
  const valid = TOKEN_RE.test(token);
  const [state, setState] = useState<State>({ kind: 'loading' });

  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!valid) return;
    let cancelled = false;
    void (async () => {
      setState({ kind: 'loading' });
      const result = await api.getSharedList(token);
      if (cancelled) return;
      if (result.ok) setState({ kind: 'ready', list: mapPublicList(result.value) });
      else setState({ kind: result.error.code === 'not_found' ? 'expired' : 'error' });
    })();
    return () => {
      cancelled = true;
    };
  }, [api, token, valid, attempt]);

  // A list link is private to whoever has it, so keep it out of search engines while the page is open.
  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'robots';
    meta.content = 'noindex';
    document.head.appendChild(meta);
    return () => meta.remove();
  }, []);

  const view: State = valid ? state : { kind: 'expired' };

  return (
    <main className={styles.page}>
      {view.kind === 'loading' ? <p className={styles.muted}>Loading…</p> : null}
      {view.kind === 'expired' ? (
        <>
          <h1 className={styles.heading}>This link has expired</h1>
          <p className={styles.muted}>Ask whoever shared it for a new link</p>
        </>
      ) : null}
      {view.kind === 'error' ? (
        <>
          <p role="alert">Could not load the list. Check your connection.</p>
          <button type="button" className={styles.retry} onClick={() => setAttempt((n) => n + 1)}>
            Retry
          </button>
        </>
      ) : null}
      {view.kind === 'ready' ? (
        <>
          <h1 className={styles.heading}>{view.list.name}</h1>
          {view.list.items.length === 0 ? (
            <p className={styles.muted}>Nothing left to buy</p>
          ) : (
            <ul className={styles.items} aria-label="Items">
              {view.list.items.map((i, n) => (
                <li key={n} className={styles.item}>
                  {i.quantity == null ? i.name : `${i.name} · ${formatQty(i.quantity, null)}`}
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}
      <p className={styles.footer}>Made with AbodeNestly</p>
    </main>
  );
}
