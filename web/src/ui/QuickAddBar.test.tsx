import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QuickAddBar } from './QuickAddBar.tsx';

afterEach(cleanup);

const suggestions = [
  { key: 'milk', label: 'Milk' },
  { key: 'mint', label: 'Mint' },
];

function Harness({ onPick, onSubmit }: { onPick: (l: string) => void; onSubmit: (t: string) => void }) {
  const [value, setValue] = useState('');
  return <QuickAddBar value={value} onChange={setValue} suggestions={value ? suggestions : []} onPick={onPick} onSubmit={onSubmit} />;
}

describe('QuickAddBar', () => {
  it('is a combobox that opens a listbox while there are suggestions', () => {
    render(<Harness onPick={vi.fn()} onSubmit={vi.fn()} />);
    const box = screen.getByRole('combobox');
    expect(box.getAttribute('aria-expanded')).toBe('false');
    fireEvent.change(box, { target: { value: 'mi' } });
    expect(box.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Milk', 'Mint']);
  });

  it('arrow keys move the highlight and Enter picks it', () => {
    const onPick = vi.fn();
    const onSubmit = vi.fn();
    render(<Harness onPick={onPick} onSubmit={onSubmit} />);
    const box = screen.getByRole('combobox');
    fireEvent.change(box, { target: { value: 'mi' } });
    fireEvent.keyDown(box, { key: 'ArrowDown' });
    fireEvent.keyDown(box, { key: 'ArrowDown' });
    expect(box.getAttribute('aria-activedescendant')).toBe(screen.getAllByRole('option')[1]!.id);
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(onPick).toHaveBeenCalledWith('Mint');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('Enter with nothing highlighted submits the typed text; Esc closes the list', () => {
    const onSubmit = vi.fn();
    render(<Harness onPick={vi.fn()} onSubmit={onSubmit} />);
    const box = screen.getByRole('combobox');
    fireEvent.change(box, { target: { value: 'Lightbulbs' } });
    fireEvent.keyDown(box, { key: 'Escape' });
    expect(screen.queryByRole('listbox')).toBeNull();
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(onSubmit).toHaveBeenCalledWith('Lightbulbs');
  });

  it('clicking a suggestion picks it; Add is disabled on blank input', () => {
    const onPick = vi.fn();
    render(<Harness onPick={onPick} onSubmit={vi.fn()} />);
    const add = screen.getByRole('button', { name: 'Add' }) as HTMLButtonElement;
    expect(add.disabled).toBe(true);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'mi' } });
    expect(add.disabled).toBe(false);
    fireEvent.click(screen.getByRole('option', { name: 'Milk' }));
    expect(onPick).toHaveBeenCalledWith('Milk');
  });
});
