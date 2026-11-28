import { useEffect, useId, useRef, useState, type KeyboardEvent, type RefObject } from 'react';
import styles from './QuickAddBar.module.css';

export type QuickAddSuggestion = { key: string; label: string };

type Props = {
  value: string;
  onChange: (value: string) => void;
  suggestions: QuickAddSuggestion[];
  /** A suggestion was chosen; the caller adds its label. */
  onPick: (label: string) => void;
  /** Enter or Add with no suggestion highlighted; the caller adds the typed text as-is. */
  onSubmit: (text: string) => void;
  placeholder?: string;
  autoFocus?: boolean;
  /** Shows a Details button that hands over the typed text (opens the item sheet). */
  onDetails?: (text: string) => void;
  /** Shows a Scan barcode button (SHOP-10); the caller opens the scanner. */
  onScan?: () => void;
  /** Shows a Voice add button (SHOP-14); the caller opens the voice sheet. */
  onVoice?: () => void;
  /** Lets the caller focus the field (the voice sheet's "Type instead"). */
  inputRef?: RefObject<HTMLInputElement | null>;
};

// DESIGN A6 QuickAddBar: field, Add button and an autocomplete listbox above it (ARIA combobox pattern).
// The barcode button is SHOP-10, the mic button SHOP-14.
export function QuickAddBar({ value, onChange, suggestions, onPick, onSubmit, placeholder = 'Add an item', autoFocus, onDetails, onScan, onVoice, inputRef }: Props) {
  const listId = useId();
  const own = useRef<HTMLInputElement>(null);
  const input = inputRef ?? own;
  const [active, setActive] = useState(-1);
  const [closed, setClosed] = useState(false);
  const open = !closed && suggestions.length > 0;

  useEffect(() => {
    setActive(-1);
    setClosed(false);
  }, [value]);
  // The list can shrink without the text changing (a live query drops a name just added); never point past its end.
  useEffect(() => {
    setActive(-1);
  }, [suggestions.length]);
  useEffect(() => {
    if (autoFocus) input.current?.focus();
  }, [autoFocus]);

  const optionId = (i: number) => `${listId}-${i}`;
  const blank = value.trim() === '';

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown' && open) {
      e.preventDefault();
      setActive((a) => (a + 1) % suggestions.length);
    } else if (e.key === 'ArrowUp' && open) {
      e.preventDefault();
      setActive((a) => (a <= 0 ? suggestions.length - 1 : a - 1));
    } else if (e.key === 'Escape' && open) {
      e.preventDefault();
      setClosed(true);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const chosen = open ? suggestions[active] : undefined;
      if (chosen) pick(chosen.label);
      else if (!blank) onSubmit(value);
    }
  };
  const pick = (label: string) => {
    onPick(label);
    input.current?.focus();
  };

  return (
    <div className={styles.bar}>
      {open ? (
        <ul id={listId} role="listbox" aria-label="Suggestions" className={styles.list}>
          {suggestions.map((s, i) => (
            <li
              key={s.key}
              id={optionId(i)}
              role="option"
              aria-selected={i === active}
              className={styles.option}
              // Keeps focus in the field so the tap is not lost to a blur.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => pick(s.label)}
            >
              {s.label}
            </li>
          ))}
        </ul>
      ) : null}
      <div className={styles.row}>
        <input
          ref={input}
          className={styles.input}
          type="text"
          role="combobox"
          aria-label="Add an item"
          aria-autocomplete="list"
          aria-expanded={open}
          aria-controls={open ? listId : undefined}
          aria-activedescendant={open && active >= 0 ? optionId(active) : undefined}
          autoComplete="off"
          placeholder={placeholder}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
        />
        {onScan ? (
          <button type="button" className={styles.scan} aria-label="Scan barcode" onClick={onScan}>
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M4 7v10M7 7v10M11 7v10M14 7v10M18 7v10M20 7v10" />
            </svg>
          </button>
        ) : null}
        {onVoice ? (
          <button type="button" className={styles.scan} aria-label="Voice add" onClick={onVoice}>
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="9" y="3" width="6" height="11" rx="3" />
              <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
            </svg>
          </button>
        ) : null}
        {onDetails ? (
          <button type="button" className={styles.details} onClick={() => onDetails(value)}>
            Details
          </button>
        ) : null}
        <button type="button" className={styles.add} disabled={blank} onClick={() => onSubmit(value)}>
          Add
        </button>
      </div>
    </div>
  );
}
