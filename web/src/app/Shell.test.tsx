import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Shell } from './Shell.tsx';

afterEach(cleanup);

describe('Shell', () => {
  it('INIT: shell renders', () => {
    render(<Shell />);
    expect(screen.getByRole('heading', { name: 'AbodeNestly' })).toBeTruthy();
    expect(screen.getByRole('main')).toBeTruthy();
  });
});
