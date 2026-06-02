import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LemmaMeta, SurveyClient, SurveyItem } from '@/application/survey';
import { clearLemmaMetaCache, RatingCard } from '@/ui/components/sondage';

const sampleItem: SurveyItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  mot: 'CHAT',
  definition: 'Animal domestique à moustaches',
  pos: 'nom_commun',
  categorie: 'faune_flore',
  style: 'definition_directe',
  forceClaimed: 2,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

function stubClient(meta: LemmaMeta): SurveyClient {
  return {
    getNextItem: vi.fn(),
    submitRating: vi.fn(),
    getNextPair: vi.fn(),
    submitPairRating: vi.fn(),
    undoAction: vi.fn(),
    getProgress: vi.fn(),
    getContributions: vi.fn(),
    patchPreferences: vi.fn(),
    getCurrentCampaign: vi.fn(),
    getLemmaMeta: vi.fn().mockResolvedValue(meta),
  };
}

function lastMeta(fn: ReturnType<typeof vi.fn>) {
  const call = fn.mock.calls[fn.mock.calls.length - 1];
  return call[2];
}

describe('RatingCard meta inputs', () => {
  beforeEach(() => { clearLemmaMetaCache(); });

  it('toggling a category adds it; verdict carries the new selection', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetCategories).toEqual(['faune_flore', 'objet']);
  });

  it('cannot drop below the seed (min 1) but can remove an added category', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const seed = container.querySelector<HTMLInputElement>('[data-categorie="faune_flore"] input')!;
    await act(async () => { fireEvent.click(seed); });
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    await act(async () => { fireEvent.click(objet); });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    // Removal of the lone seed was blocked; the added category was removed.
    expect(lastMeta(onVerdict).targetCategories).toEqual(['faune_flore']);
  });

  it('caps category selection at 6', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const cats = ['objet', 'corps', 'culture', 'histoire', 'jeu', 'sport', 'religion'];
    for (const c of cats) {
      const input = container.querySelector<HTMLInputElement>(`[data-categorie="${c}"] input`)!;
      await act(async () => { fireEvent.click(input); });
    }
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetCategories).toHaveLength(6);
  });

  it('checking "autre" clears every other category', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    const autre = container.querySelector<HTMLInputElement>('[data-categorie="autre"] input')!;
    await act(async () => { fireEvent.click(autre); });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetCategories).toEqual(['autre']);
  });

  it('checking another category clears a previously selected "autre"', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const autre = container.querySelector<HTMLInputElement>('[data-categorie="autre"] input')!;
    await act(async () => { fireEvent.click(autre); });
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetCategories).toEqual(['objet']);
  });

  it('announces all cleared when "autre" replaces other selections', async () => {
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={async () => {}} onCorriger={async () => {}} />,
    );
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    const autre = container.querySelector<HTMLInputElement>('[data-categorie="autre"] input')!;
    await act(async () => { fireEvent.click(autre); });
    const liveRegion = container.querySelector('[data-testid="categorie-multiselect"] [role="status"]')!;
    expect(liveRegion.textContent).toContain('retirées');
  });

  it('announces "autre" removed when a non-exclusive category is selected', async () => {
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={async () => {}} onCorriger={async () => {}} />,
    );
    const autre = container.querySelector<HTMLInputElement>('[data-categorie="autre"] input')!;
    await act(async () => { fireEvent.click(autre); });
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    const liveRegion = container.querySelector('[data-testid="categorie-multiselect"] [role="status"]')!;
    expect(liveRegion.textContent).toContain('Autre');
    expect(liveRegion.textContent).toContain('retirée');
  });

  it('autre is still clickable when 6 categories are already selected', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const caps = ['objet', 'corps', 'culture', 'histoire', 'jeu', 'sport'];
    for (const c of caps) {
      await act(async () => {
        fireEvent.click(container.querySelector<HTMLInputElement>(`[data-categorie="${c}"] input`)!);
      });
    }
    const autreInput = container.querySelector<HTMLInputElement>('[data-categorie="autre"] input')!;
    expect(autreInput.disabled).toBe(false);
    await act(async () => { fireEvent.click(autreInput); });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetCategories).toEqual(['autre']);
  });

  it('typing a single sense threads it into the verdict meta', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const sense = screen.getByRole('combobox', { name: 'Sens cible' }) as HTMLInputElement;
    await act(async () => { fireEvent.change(sense, { target: { value: 'animal félin' } }); });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetSense).toBe('animal félin');
    expect(lastMeta(onVerdict).isMultisense).toBe(false);
  });

  it('the lemma cannot be entered as a sense (ADR-0061 repetition rule)', async () => {
    render(<RatingCard item={sampleItem} onVerdict={async () => {}} onCorriger={async () => {}} />);
    const sense = screen.getByRole('combobox', { name: 'Sens cible' }) as HTMLInputElement;
    await act(async () => { fireEvent.change(sense, { target: { value: 'le chat' } }); });
    expect(sense.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByRole('alert')).toHaveTextContent(/ne doit pas répéter/i);
  });

  it('checking calembour disables the single-sense input and keeps the flag in meta', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const sense = screen.getByRole('combobox', { name: 'Sens cible' }) as HTMLInputElement;
    await act(async () => { fireEvent.change(sense, { target: { value: 'animal félin' } }); });
    await act(async () => {
      fireEvent.click(screen.getByTestId('multisense-checkbox'));
    });
    expect(sense.disabled).toBe(true);
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).isMultisense).toBe(true);
    // When multisense, the single sense is dropped by the route, but the card still reports the flag.
    expect(lastMeta(onVerdict).targetSense).toBe('animal félin');
  });

  it('adds and removes sub-tags; verdict carries them', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const subInput = screen.getByRole('combobox', { name: 'Mots-clés' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.change(subInput, { target: { value: 'félin' } });
      fireEvent.keyDown(subInput, { key: 'Enter' });
    });
    await act(async () => {
      fireEvent.change(subInput, { target: { value: 'domestique' } });
      fireEvent.keyDown(subInput, { key: 'Enter' });
    });
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).subTags).toEqual(['félin', 'domestique']);
  });

  it('sub-tags start empty per item (no prior prefill)', async () => {
    const client = stubClient({ priorSenses: [], priorSubTags: ['ancien-tag'] });
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} surveyClient={client} />,
    );
    await waitFor(() => expect(client.getLemmaMeta).toHaveBeenCalled());
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).subTags).toEqual([]);
  });

  it('autocompletes sub-tags and senses from lemma-meta priors', async () => {
    const client = stubClient({ priorSenses: ['conversation digitale'], priorSubTags: ['capitale'] });
    render(<RatingCard item={sampleItem} onVerdict={async () => {}} onCorriger={async () => {}} surveyClient={client} />);
    await waitFor(() => expect(client.getLemmaMeta).toHaveBeenCalled());
    const sense = screen.getByRole('combobox', { name: 'Sens cible' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.focus(sense);
      fireEvent.change(sense, { target: { value: 'conv' } });
    });
    expect(screen.getByRole('listbox', { name: 'Sens cible' }).textContent).toContain('conversation digitale');
  });

  it('resets meta to the item prior when the item changes', async () => {
    const onVerdict = vi.fn().mockResolvedValue(undefined);
    const { container, rerender } = render(
      <RatingCard item={sampleItem} onVerdict={onVerdict} onCorriger={async () => {}} />,
    );
    const objet = container.querySelector<HTMLInputElement>('[data-categorie="objet"] input')!;
    await act(async () => { fireEvent.click(objet); });
    const next: SurveyItem = { ...sampleItem, itemId: 'next-id', mot: 'BANQUE', categorie: 'societe' };
    rerender(<RatingCard item={next} onVerdict={onVerdict} onCorriger={async () => {}} />);
    await act(async () => { fireEvent.click(container.querySelector('[data-verdict="GOOD"]')!); });
    expect(lastMeta(onVerdict).targetCategories).toEqual(['societe']);
  });
});
