import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Stepper } from './Stepper.tsx';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function Harness({ start = '' }: { start?: string }) {
  const [v, setV] = useState(start);
  return <Stepper value={v} onChange={setV} />;
}
const field = () => screen.getByRole('textbox', { name: 'Quantity' }) as HTMLInputElement;

describe('Stepper', () => {
  it('+ on empty gives 1, then counts up; − from 1 clears', () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole('button', { name: 'Increase quantity' }));
    expect(field().value).toBe('1');
    fireEvent.click(screen.getByRole('button', { name: 'Increase quantity' }));
    expect(field().value).toBe('2');
    fireEvent.click(screen.getByRole('button', { name: 'Decrease quantity' }));
    expect(field().value).toBe('1');
    fireEvent.click(screen.getByRole('button', { name: 'Decrease quantity' }));
    expect(field().value).toBe('');
  });

  it('accepts typed decimals with a comma', () => {
    render(<Harness />);
    fireEvent.change(field(), { target: { value: '0,5' } });
    expect(field().value).toBe('0,5');
  });

  it('holding + repeats after a delay and the release click adds nothing', () => {
    vi.useFakeTimers();
    render(<Harness start="1" />);
    const plus = screen.getByRole('button', { name: 'Increase quantity' });
    fireEvent.pointerDown(plus);
    act(() => void vi.advanceTimersByTime(399));
    expect(field().value).toBe('1');
    act(() => void vi.advanceTimersByTime(1));
    expect(field().value).toBe('2');
    act(() => void vi.advanceTimersByTime(300));
    expect(field().value).toBe('5');
    fireEvent.pointerUp(plus);
    fireEvent.click(plus);
    expect(field().value).toBe('5');
    act(() => void vi.advanceTimersByTime(1000));
    expect(field().value).toBe('5');
  });

  it('marks the field invalid when asked', () => {
    render(<Stepper value="x" onChange={() => undefined} invalid />);
    expect(field().getAttribute('aria-invalid')).toBe('true');
  });
});
