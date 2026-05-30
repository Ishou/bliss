import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ItemPair, SurveyItem } from '@/application/survey';
import { PairCard, RatingCard } from '@/ui/components/sondage';

const sampleItem: SurveyItem = {
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

const samplePair: ItemPair = {
  mot: 'CHAT',
  left: sampleItem,
  right: { ...sampleItem, itemId: '0190e3a4-7a2c-7c9e-8f1a-cafecafecafe', definition: 'Felin' },
};

describe('RatingCard disabled', () => {
  it('marks verdict buttons aria-disabled and short-circuits clicks', async () => {
    const onVerdict = vi.fn();
    const { container } = render(
      <RatingCard
        item={sampleItem}
        onVerdict={onVerdict}
        onCorriger={vi.fn()}
        disabled={true}
      />,
    );
    for (const verdict of ['BAD', 'SKIP', 'GOOD'] as const) {
      const btn = container.querySelector<HTMLButtonElement>(`[data-verdict="${verdict}"]`)!;
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      await act(async () => { fireEvent.click(btn); });
    }
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('uses aria-disabled rather than the html disabled attribute', () => {
    const { container } = render(
      <RatingCard
        item={sampleItem}
        onVerdict={vi.fn()}
        onCorriger={vi.fn()}
        disabled={true}
      />,
    );
    const btn = container.querySelector<HTMLButtonElement>('[data-verdict="GOOD"]')!;
    expect(btn.hasAttribute('disabled')).toBe(false);
    expect(btn.getAttribute('aria-disabled')).toBe('true');
  });

  it('hides the Corriger entry button when disabled', () => {
    const { container } = render(
      <RatingCard
        item={sampleItem}
        onVerdict={vi.fn()}
        onCorriger={vi.fn()}
        disabled={true}
      />,
    );
    expect(container.querySelector('[data-verdict="CORRIGER"]')).toBeNull();
  });

  it('keyboard shortcuts j/k/l are ignored when disabled', async () => {
    const onVerdict = vi.fn();
    render(
      <RatingCard
        item={sampleItem}
        onVerdict={onVerdict}
        onCorriger={vi.fn()}
        disabled={true}
      />,
    );
    await act(async () => {
      fireEvent.keyDown(window, { key: 'j' });
      fireEvent.keyDown(window, { key: 'k' });
      fireEvent.keyDown(window, { key: 'l' });
      fireEvent.keyDown(window, { key: 'c' });
    });
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('collapses an open correctif panel when lock arrives mid-flight', () => {
    const { container, rerender } = render(
      <RatingCard
        item={sampleItem}
        onVerdict={vi.fn()}
        onCorriger={vi.fn()}
        disabled={false}
      />,
    );
    const corriger = container.querySelector<HTMLButtonElement>('[data-verdict="CORRIGER"]')!;
    fireEvent.click(corriger);
    expect(container.querySelector('[data-testid="correctif-box"]')).not.toBeNull();
    rerender(
      <RatingCard
        item={sampleItem}
        onVerdict={vi.fn()}
        onCorriger={vi.fn()}
        disabled={true}
      />,
    );
    expect(container.querySelector('[data-testid="correctif-box"]')).toBeNull();
  });
});

describe('PairCard disabled', () => {
  it('marks pair verdict buttons aria-disabled and short-circuits clicks', async () => {
    const onVerdict = vi.fn();
    const { container } = render(
      <PairCard pair={samplePair} onVerdict={onVerdict} disabled={true} />,
    );
    for (const verdict of ['LEFT_WINS', 'RIGHT_WINS', 'BOTH_GOOD', 'BOTH_BAD', 'SKIP'] as const) {
      const btn = container.querySelector<HTMLButtonElement>(`[data-verdict="${verdict}"]`)!;
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      expect(btn.hasAttribute('disabled')).toBe(false);
      await act(async () => { fireEvent.click(btn); });
    }
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('ignores keyboard shortcuts when disabled', async () => {
    const onVerdict = vi.fn();
    render(<PairCard pair={samplePair} onVerdict={onVerdict} disabled={true} />);
    await act(async () => {
      fireEvent.keyDown(window, { key: 'a' });
      fireEvent.keyDown(window, { key: 'd' });
      fireEvent.keyDown(window, { key: 's' });
      fireEvent.keyDown(window, { key: 'x' });
      fireEvent.keyDown(window, { key: ' ' });
    });
    expect(onVerdict).not.toHaveBeenCalled();
  });
});
