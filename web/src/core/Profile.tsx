import { useEffect, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react';
import { Link } from 'react-router';
import { OfflineBanner } from '../ui/OfflineBanner.tsx';
import { useOnline } from '../ui/useOnline.ts';
import { useAuth } from './auth/AuthProvider.tsx';
import { SignOutButton } from './auth/SignOutButton.tsx';
import { isStoragePath } from './Profile.ts';
import styles from './Profile.module.css';

const initials = (name: string) => name.trim().slice(0, 1).toUpperCase() || '?';

// DESIGN B7. Until web sync exists the name edit is a localStorage outbox entry and a picked photo is
// held in memory (see AuthProvider); both are applied by an interim drain when online.
export function Profile() {
  const online = useOnline();
  const { session, profile, client, avatar, changeAvatar, editName, syncProblem } = useAuth();
  const fileInput = useRef<HTMLInputElement>(null);
  const [nameDraft, setNameDraft] = useState<string | null>(null);
  const [signed, setSigned] = useState<{ path: string; url: string } | null>(null);
  const [badFile, setBadFile] = useState(false);

  const avatarPath = profile?.avatar_path ?? null;
  useEffect(() => {
    if (!avatarPath || !isStoragePath(avatarPath)) return;
    let active = true;
    void client.avatarUrl(avatarPath).then((url) => {
      if (active && url) setSigned({ path: avatarPath, url });
    });
    return () => {
      active = false;
    };
  }, [client, avatarPath]);

  const name = nameDraft ?? profile?.display_name ?? '';
  let imageUrl: string | null = null;
  if (avatar.preview) imageUrl = avatar.preview;
  else if (avatarPath && !isStoragePath(avatarPath)) imageUrl = avatarPath;
  else if (signed && signed.path === avatarPath) imageUrl = signed.url;

  // editName ignores an unchanged or blank name, so Enter followed by the blur it causes saves once.
  function commitName() {
    editName(name);
    setNameDraft(null);
  }

  function onNameKey(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') commitName();
  }

  function onPick(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file) setBadFile(!changeAvatar(file));
  }

  const uploading = avatar.status === 'uploading';

  return (
    <div className={styles.page}>
      {!online ? <OfflineBanner message="You're offline. Changes will sync when you reconnect." /> : null}

      <button
        type="button"
        className={styles.avatar}
        aria-label="Change photo"
        aria-busy={uploading}
        onClick={() => fileInput.current?.click()}
      >
        {imageUrl ? <img src={imageUrl} alt="" className={styles.photo} /> : <span aria-hidden="true">{initials(name)}</span>}
      </button>
      <input
        ref={fileInput}
        type="file"
        accept="image/*"
        hidden
        data-testid="avatar-file"
        onChange={onPick}
      />
      <p aria-live="polite" className={styles.note}>
        {uploading ? 'Uploading photo…' : avatar.status === 'queued' ? "Photo will upload when you're online" : ''}
      </p>
      {badFile ? (
        <p role="alert" className={styles.error}>
          Choose a JPG, PNG, WebP or GIF photo.
        </p>
      ) : null}
      {syncProblem ? (
        <p role="alert" className={styles.error}>
          Some changes couldn't be saved.
        </p>
      ) : null}

      <label className={styles.field}>
        <span className={styles.label}>Display name</span>
        <input
          className={styles.input}
          value={name}
          disabled={!profile}
          maxLength={80}
          onChange={(e) => setNameDraft(e.target.value)}
          onBlur={commitName}
          onKeyDown={onNameKey}
        />
      </label>

      <label className={styles.field}>
        <span className={styles.label}>Email</span>
        <input className={styles.input} value={session?.user.email ?? ''} readOnly />
      </label>

      <Link to="/profile/notifications" className={styles.link}>
        Notification preferences
      </Link>

      <div className={styles.signOut}>
        <SignOutButton />
      </div>
    </div>
  );
}
