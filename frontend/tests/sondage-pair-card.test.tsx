import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { ItemPair, SurveyItem } from '@/application/survey';
import { PairCard } from '@/ui/components/sondage';

const leftItem: SurveyItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  mot: 'CHAT',
  definition: 'Animal domestique à moustaches',
  pos: 'nom_commun',
  categorie: 'animals',
  style: 'definition_directe',
  forceClaimed: 2,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

const rightItem: SurveyItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-cafecafecafe',
  mot: 'CHAT',
  definition: 'Félin domestique aux iris fendus',
  pos: 'nom_commun',
  categorie: 'animals',
  style: 'periphrase',
  forceClaimed: 3,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

const samplePair: ItemPair = { mot: 'CHAT', left: leftItem, right: rightItem };

describe('PairCard', () => {
  it('renders the mot once and both definitions in side panels', () => {
    const { container } = render(<PairCard pair={samplePair} onVerdict={() => Promise.resolve()} />);
    expect(screen.getByRole('heading', { name: 'CHAT', level: 2 })).toBeInTheDocument();
    expect(screen.getByText(/Animal domestique à moustaches/)).toBeInTheDocument();
    expect(screen.getByText(/Félin domestique aux iris fendus/)).toBeInTheDocument();
    expect(container.querySelector('[data-side="left"]')).not.toBeNull();
    expect(container.querySelector('[data-side="right"]')).not.toBeNull();
  });

  it('renders all five verdict buttons with min touch-target class', () => {
    const { container } = render(<PairCard pair={samplePair} onVerdict={() => Promise.resolve()} />);
    for (const verdict of ['LEFT_WINS', 'RIGHT_WINS', 'BOTH_GOOD', 'BOTH_BAD', 'SKIP'] as const) {
      const btn = container.querySelector<HTMLButtonElement>(`[data-verdict="${verdict}"]`);
      expect(btn, `missing button ${verdict}`).not.toBeNull();
      expect(btn!.className).toMatch(/min/i);
      expect(btn!.getAttribute('aria-label')).toBeTruthy();
    }
  });

  it('exposes the Verdict role=group with aria-keyshortcuts', () => {
    render(<PairCard pair={samplePair} onVerdict={() => Promise.resolve()} />);
    const group = screen.getByRole('group', { name: /Verdict pairwise/i });
    expect(group.getAttribute('aria-keyshortcuts')).toBe('a d s x space escape');
  });

  it('clicking LEFT_WINS invokes onVerdict("LEFT_WINS", latencyMs >= 0)', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<PairCard pair={samplePair} onVerdict={onVerdict} />);
    await act(async () => {
      fireEvent.click(container.querySelector('[data-verdict="LEFT_WINS"]')!);
    });
    expect(onVerdict).toHaveBeenCalledTimes(1);
    expect(onVerdict.mock.calls[0][0]).toBe('LEFT_WINS');
    expect(onVerdict.mock.calls[0][1]).toBeGreaterThanOrEqual(0);
  });

  it.each([
    ['RIGHT_WINS'],
    ['BOTH_GOOD'],
    ['BOTH_BAD'],
    ['SKIP'],
  ] as const)('clicking %s invokes onVerdict with that verdict', async (verdict) => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<PairCard pair={samplePair} onVerdict={onVerdict} />);
    await act(async () => {
      fireEvent.click(container.querySelector(`[data-verdict="${verdict}"]`)!);
    });
    expect(onVerdict).toHaveBeenCalledWith(verdict, expect.any(Number));
  });

  it('keyboard shortcuts a/d/s/x map to LEFT_WINS/RIGHT_WINS/BOTH_GOOD/BOTH_BAD', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    render(<PairCard pair={samplePair} onVerdict={onVerdict} />);
    await act(async () => { fireEvent.keyDown(window, { key: 'a' }); });
    expect(onVerdict).toHaveBeenLastCalledWith('LEFT_WINS', expect.any(Number));
    await act(async () => { fireEvent.keyDown(window, { key: 'd' }); });
    expect(onVerdict).toHaveBeenLastCalledWith('RIGHT_WINS', expect.any(Number));
    await act(async () => { fireEvent.keyDown(window, { key: 's' }); });
    expect(onVerdict).toHaveBeenLastCalledWith('BOTH_GOOD', expect.any(Number));
    await act(async () => { fireEvent.keyDown(window, { key: 'x' }); });
    expect(onVerdict).toHaveBeenLastCalledWith('BOTH_BAD', expect.any(Number));
  });

  it('space and Escape both map to SKIP', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    render(<PairCard pair={samplePair} onVerdict={onVerdict} />);
    await act(async () => { fireEvent.keyDown(window, { key: ' ' }); });
    expect(onVerdict).toHaveBeenLastCalledWith('SKIP', expect.any(Number));
    await act(async () => { fireEvent.keyDown(window, { key: 'Escape' }); });
    expect(onVerdict).toHaveBeenLastCalledWith('SKIP', expect.any(Number));
  });

  it('ignores modifier-key chords (Cmd/Ctrl/Alt + a)', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    render(<PairCard pair={samplePair} onVerdict={onVerdict} />);
    await act(async () => {
      fireEvent.keyDown(window, { key: 'a', metaKey: true });
      fireEvent.keyDown(window, { key: 'd', ctrlKey: true });
      fireEvent.keyDown(window, { key: 's', altKey: true });
    });
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('ignores keys typed in an INPUT/TEXTAREA', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    render(
      <>
        <input data-testid="probe-input" />
        <PairCard pair={samplePair} onVerdict={onVerdict} />
      </>,
    );
    const input = screen.getByTestId('probe-input');
    await act(async () => {
      fireEvent.keyDown(input, { key: 'a' });
    });
    expect(onVerdict).not.toHaveBeenCalled();
  });
});
