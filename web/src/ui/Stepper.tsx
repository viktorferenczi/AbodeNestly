import { useEffect, useRef } from 'react';
import { stepQuantity } from '../lib/quantity.ts';
import styles from './Stepper.module.css';

type Props = { value: string; onChange: (text: string) => void; label?: string; invalid?: boolean };

const HOLD_DELAY = 400;
const HOLD_EVERY = 100;

// DESIGN A6 Stepper. The value is the field text, so "0,5" and half-typed numbers survive; the caller validates.
// Holding − or + repeats. A hold must not also fire the click that follows it.
export function Stepper({ value, onChange, label = 'Quantity', invalid }: Props) {
  const latest = useRef(value);
  latest.current = value;
  const timers = useRef<{ delay?: ReturnType<typeof setTimeout>; every?: ReturnType<typeof setInterval> }>({});
  const held = useRef(false);

  const stop = () => {
    clearTimeout(timers.current.delay);
    clearInterval(timers.current.every);
    timers.current = {};
  };
  useEffect(() => stop, []);

  const step = (dir: 1 | -1) => {
    const next = stepQuantity(latest.current, dir);
    latest.current = next;
    onChange(next);
  };
  const press = (dir: 1 | -1) => ({
    onPointerDown: () => {
      held.current = false;
      stop();
      timers.current.delay = setTimeout(() => {
        held.current = true;
        step(dir);
        timers.current.every = setInterval(() => step(dir), HOLD_EVERY);
      }, HOLD_DELAY);
    },
    onPointerUp: stop,
    onPointerLeave: stop,
    onPointerCancel: stop,
    onClick: () => {
      if (held.current) held.current = false;
      else step(dir);
    },
  });

  return (
    <div className={styles.stepper}>
      <button type="button" className={styles.button} aria-label="Decrease quantity" {...press(-1)}>
        −
      </button>
      <input
        className={styles.input}
        inputMode="decimal"
        autoComplete="off"
        aria-label={label}
        aria-invalid={invalid || undefined}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <button type="button" className={styles.button} aria-label="Increase quantity" {...press(1)}>
        +
      </button>
    </div>
  );
}
