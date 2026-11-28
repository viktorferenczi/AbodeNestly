import { useId, useState, type FormEvent } from 'react';
import { parseQuantity } from '../lib/quantity.ts';
import { Sheet } from '../ui/Sheet.tsx';
import { Stepper } from '../ui/Stepper.tsx';
import { lastBoughtLine, parsePrice, type LastBought } from './Price.ts';
import { formFrom, toFields, type ItemFields } from './ItemSheet.ts';
import styles from './ItemSheet.module.css';

/** SHOP-12: what the sheet needs to offer a price. `storeName` is null when the list has no live store: no field then. */
export type Pricing = { storeName: string | null; currency: string; lastBought: (name: string) => LastBought | null };

type Props = {
  mode: 'new' | 'edit';
  /** The row being edited, or null for a new item. */
  item: Pick<ItemFields, 'name' | 'quantity' | 'unit' | 'note'> | null;
  /** Name typed in the quick add bar, for a new item. */
  initialName?: string;
  units: string[];
  pricing?: Pricing;
  /** `price` is null when the field was left empty: nothing is recorded then. */
  onSave: (fields: ItemFields, price: number | null) => Promise<void>;
  onDelete?: () => void;
  onClose: () => void;
};

// DESIGN C3: name, quantity stepper, unit, note, and (SHOP-12) the price at the list's store with the "Last bought" line.
export function ItemSheet({ mode, item, initialName = '', units, pricing, onSave, onDelete, onClose }: Props) {
  const [form, setForm] = useState(() => formFrom(item, initialName));
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const unitsId = useId();
  const errorId = useId();
  const priceErrorId = useId();
  const priceId = useId();
  const [priceText, setPriceText] = useState('');
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  const badQuantity = !parseQuantity(form.quantity).ok;
  const parsedPrice = parsePrice(priceText);
  const showPrice = pricing?.storeName != null;
  const badPrice = showPrice && !parsedPrice.ok;
  const fields = badPrice ? null : toFields(form);
  const last = pricing && form.name.trim() ? pricing.lastBought(form.name) : null;

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!fields || busy) return;
    setBusy(true);
    setFailed(false);
    try {
      await onSave(fields, showPrice && parsedPrice.ok ? parsedPrice.value : null);
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
          <Stepper value={form.quantity} onChange={(quantity) => set({ quantity })} invalid={badQuantity} describedBy={badQuantity ? errorId : undefined} />
          {badQuantity ? (
            <p id={errorId} role="alert" className={styles.error}>
              Enter a number above 0, with at most 3 decimals
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
        {showPrice && pricing ? (
          <div className={styles.field}>
            <label htmlFor={priceId}>Price at {pricing.storeName}</label>
            <input
              id={priceId}
              className={styles.price}
              inputMode="decimal"
              value={priceText}
              onChange={(e) => setPriceText(e.target.value)}
              autoComplete="off"
              aria-invalid={badPrice || undefined}
              aria-describedby={badPrice ? priceErrorId : undefined}
            />
            {badPrice ? (
              <p id={priceErrorId} role="alert" className={styles.error}>
                Enter a price like 1.20
              </p>
            ) : null}
          </div>
        ) : null}
        {last && pricing ? <p className={styles.last}>{lastBoughtLine(last, pricing.currency)}</p> : null}
        {failed ? <p role="alert">Could not save the item. Try again.</p> : null}
        {/* Enter in a single-line field submits; the visible Save lives in the footer. */}
        <button type="submit" hidden />
      </form>
    </Sheet>
  );
}
