import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NewListSheet } from './NewListSheet.tsx';

afterEach(cleanup);

describe('SHOP-01: NewListSheet', () => {
  it('a failed save re-enables Save so it can be retried', async () => {
    const onSave = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(undefined);
    render(<NewListSheet stores={[]} onSave={onSave} onClose={() => undefined} />);
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Hardware' } });
    const save = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    fireEvent.click(save);
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(save.disabled).toBe(false));
    fireEvent.click(save);
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(2));
  });
});
