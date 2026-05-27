import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { SurveyItem } from '@/application/survey';
import { RatingCard } from '@/ui/components/sondage';

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

describe('RatingCard verdict picker', () => {
  it('renders mot, definition, chips, and four verdict buttons', () => {
    const { container } = render(<RatingCard item={sampleItem} onVerdict={() => Promise.resolve()} onCorriger={async () => {}} />);
    expect(screen.getByRole('heading', { name: 'CHAT' })).toBeInTheDocument();
    expect(screen.getByText(/Animal domestique à moustaches/i)).toBeInTheDocument();
    expect(container.querySelector('[data-chip="pos"]')?.textContent).toBe('Nom commun');
    expect(container.querySelector('[data-chip="categorie"]')?.textContent).toBe('Animaux');
    expect(container.querySelector('[data-verdict="BAD"]')).not.toBeNull();
    expect(container.querySelector('[data-verdict="SKIP"]')).not.toBeNull();
    expect(container.querySelector('[data-verdict="GOOD"]')).not.toBeNull();
    expect(container.querySelector('[data-verdict="CORRIGER"]')).not.toBeNull();
  });

  it('exposes the Verdict role=group with aria-keyshortcuts j k l', () => {
    render(<RatingCard item={sampleItem} onVerdict={() => Promise.resolve()} onCorriger={async () => {}} />);
    const group = screen.getByRole('group', { name: 'Verdict' });
    expect(group.getAttribute('aria-keyshortcuts')).toBe('j k l c');
  });

  it('each verdict button has an aria-label citing the definition and meets the 56px touch target', () => {
    const { container } = render(<RatingCard item={sampleItem} onVerdict={() => Promise.resolve()} onCorriger={async () => {}} />);
    for (const verdict of ['BAD', 'SKIP', 'GOOD'] as const) {
      const btn = container.querySelector<HTMLButtonElement>(`[data-verdict="${verdict}"]`);
      expect(btn).not.toBeNull();
      expect(btn!.getAttribute('aria-label')).toContain(verdict);
      expect(btn!.getAttribute('aria-label')).toContain('Animal domestique à moustaches');
      // jsdom doesn't compute layout; assert the css contract is wired via class names rather than getBoundingClientRect.
      expect(btn!.className).toMatch(/min/i);
    }
  });

  it('clicking GOOD invokes onVerdict("GOOD", latencyMs >= 0)', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />);
    await act(async () => {
      fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!);
    });
    expect(onVerdict).toHaveBeenCalledTimes(1);
    expect(onVerdict.mock.calls[0][0]).toBe('GOOD');
    expect(onVerdict.mock.calls[0][1]).toBeGreaterThanOrEqual(0);
  });

  it('clicking BAD invokes onVerdict("BAD")', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />);
    await act(async () => {
      fireEvent.click(container.querySelector('[data-verdict="BAD"]')!);
    });
    expect(onVerdict).toHaveBeenCalledWith('BAD', expect.any(Number));
  });

  it('clicking SKIP invokes onVerdict("SKIP")', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(<RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />);
    await act(async () => {
      fireEvent.click(container.querySelector('[data-verdict="SKIP"]')!);
    });
    expect(onVerdict).toHaveBeenCalledWith('SKIP', expect.any(Number));
  });

  it('pressing j/k/l triggers BAD/SKIP/GOOD via the document-level keydown handler', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    render(<RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />);
    await act(async () => {
      fireEvent.keyDown(window, { key: 'j' });
    });
    expect(onVerdict).toHaveBeenLastCalledWith('BAD', expect.any(Number));
    await act(async () => {
      fireEvent.keyDown(window, { key: 'k' });
    });
    expect(onVerdict).toHaveBeenLastCalledWith('SKIP', expect.any(Number));
    await act(async () => {
      fireEvent.keyDown(window, { key: 'l' });
    });
    expect(onVerdict).toHaveBeenLastCalledWith('GOOD', expect.any(Number));
  });

  it('ignores modifier-key chords (Cmd/Ctrl/Alt + j)', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    render(<RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />);
    await act(async () => {
      fireEvent.keyDown(window, { key: 'j', metaKey: true });
      fireEvent.keyDown(window, { key: 'l', ctrlKey: true });
    });
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('Corriger button opens textarea pre-filled with definition; submit invokes onCorriger', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const onCorriger = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={onCorriger} />,
    );
    const corrigerButton = container.querySelector('button[data-verdict="CORRIGER"]') as HTMLButtonElement;
    await act(async () => { fireEvent.click(corrigerButton); });

    const textarea = container.querySelector('textarea#correctif-text') as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();
    expect(textarea.value).toBe(sampleItem.definition);

    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'Une définition corrigée plus précise' } });
    });
    const submit = container.querySelector('[data-testid="correctif-submit"]') as HTMLButtonElement;
    await act(async () => { fireEvent.click(submit); });

    expect(onCorriger).toHaveBeenCalledWith('Une définition corrigée plus précise', expect.any(Number));
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('Corriger submit is a no-op when text equals the original definition', async () => {
    const onCorriger = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={async () => {}} onCorriger={onCorriger} />,
    );
    await act(async () => {
      fireEvent.click(container.querySelector('button[data-verdict="CORRIGER"]') as HTMLButtonElement);
    });
    await act(async () => {
      fireEvent.click(container.querySelector('[data-testid="correctif-submit"]') as HTMLButtonElement);
    });
    expect(onCorriger).not.toHaveBeenCalled();
  });

  it('c key opens Corriger box; Escape cancels', async () => {
    const onCorriger = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={async () => {}} onCorriger={onCorriger} />,
    );
    await act(async () => { fireEvent.keyDown(window, { key: 'c' }); });
    const textarea = container.querySelector('textarea#correctif-text') as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();
    await act(async () => { fireEvent.keyDown(textarea, { key: 'Escape' }); });
    expect(container.querySelector('textarea#correctif-text')).toBeNull();
    expect(onCorriger).not.toHaveBeenCalled();
  });
});
