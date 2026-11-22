import { useId, useState, type FormEvent } from 'react';
import { parseQuantity } from '../lib/quantity.ts';
import { Sheet } from '../ui/Sheet.tsx';
import { Stepper } from '../ui/Stepper.tsx';
import { formFrom, toFields, type ItemFields } from './ItemSheet.ts';
import styles from './ItemSheet.module.css';

type Props = {
  mode: 'new' | 'edit';
  /** The row being edited, or null for a new item. */
  item: Pick<ItemFields, 'name' | 'quantity' | 'unit' | 'note'> | null;
  /** Name typed in the quick add bar, for a new item. */
  initialName?: string;
  units: string[];
  onSave: (fields: ItemFields) => Promise<void>;
  onDelete?: () => void;
  onClose: () => void;
};

// DESIGN C3: name, quantity stepper, unit and note. Price and "Last bought" arrive with SHOP-12.
export function ItemSheet({ mode, item, initialName = '', units, onSave, onDelete, onClose }: Props) {
  const [form, setForm] = useState(() => formFrom(item, initialName));
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const unitsId = useId();
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  const badQuantity = !parseQuantity(form.quantity).ok;
  const fields = toFields(form);

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!fields || busy) return;
    setBusy(true);
    setFailed(false);
    try {
      await onSave(fields);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet
      title={mode === 'new' ? 'New item' : 'Edit item'}
      onClose={onClose}
      footer={
        <div className={styles.footer}>
          {mode === 'edit' && onDelete ? (
            <button type="button" className={styles.delete} onClick={onDelete}>
              Delete
            </button>
          ) : null}
          <button type="button" className={styles.primary} disabled={!fields || busy} onClick={() => void submit()}>
            Save
          </button>
        </div>
      }
    >
      <form className={styles.form} onSubmit={(e) => void submit(e)}>
        <label className={styles.field}>
          Name
          <input value={form.name} onChange={(e) => set({ name: e.target.value })} autoComplete="off" />
        </label>
        <div className={styles.field}>
          <span>Quantity</span>
          <Stepper value={form.quantity} onChange={(quantity) => set({ quantity })} invalid={badQuantity} />
          {badQuantity ? (
            <p role="alert" className={styles.error}>
              Enter a number above 0
            </p>
          ) : null}
        </div>
        <label className={styles.field}>
          Unit
          <input value={form.unit} onChange={(e) => set({ unit: e.target.value })} list={unitsId} autoComplete="off" />
          <datalist id={unitsId}>
            {units.map((u) => (
              <option key={u} value={u} />
            ))}
          </datalist>
        </label>
        <label className={styles.field}>
          Note
          <textarea rows={3} value={form.note} onChange={(e) => set({ note: e.target.value })} />
        </label>
        {failed ? <p role="alert">Could not save the item. Try again.</p> : null}
        {/* Enter in a single-line field submits; the visible Save lives in the footer. */}
        <button type="submit" hidden />
      </form>
    </Sheet>
  );
}
