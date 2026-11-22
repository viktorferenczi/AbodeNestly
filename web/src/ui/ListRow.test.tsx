import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ListRow } from './ListRow.tsx';

afterEach(cleanup);

function swipe(el: HTMLElement, from: number, to: number) {
  fireEvent.pointerDown(el, { clientX: from });
  fireEvent.pointerMove(el, { clientX: to });
  fireEvent.pointerUp(el, { clientX: to });
}

describe('ListRow', () => {
  it('fires on a swipe past the threshold, not on a short one', () => {
    const onSwipeLeft = vi.fn();
    render(<ul><ListRow title="Groceries" onOpen={() => undefined} onSwipeLeft={onSwipeLeft} /></ul>);
    const surface = screen.getByRole('button', { name: /Groceries/ }).parentElement!;
    swipe(surface, 200, 150);
    expect(onSwipeLeft).not.toHaveBeenCalled();
    swipe(surface, 200, 100);
    expect(onSwipeLeft).toHaveBeenCalledTimes(1);
  });

  it('a tap opens, a drag does not', () => {
    const onOpen = vi.fn();
    render(<ul><ListRow title="Groceries" onOpen={onOpen} onSwipeLeft={() => undefined} /></ul>);
    const main = screen.getByRole('button', { name: /Groceries/ });
    fireEvent.click(main);
    expect(onOpen).toHaveBeenCalledTimes(1);
    swipe(main.parentElement!, 200, 190);
    fireEvent.click(main);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('renders the action buttons it is given', () => {
    const onClick = vi.fn();
    render(<ul><ListRow title="Groceries" onOpen={() => undefined} actions={<button type="button" aria-label="Archive Groceries" onClick={onClick}>Archive</button>} /></ul>);
    fireEvent.click(screen.getByRole('button', { name: 'Archive Groceries' }));
    expect(onClick).toHaveBeenCalled();
  });
});
