import { useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { GlossChipInput } from '@/ui/components/sondage';

function ControlledHarness(props: {
  readonly initial?: ReadonlyArray<string>;
  readonly suggestions?: ReadonlyArray<string>;
  readonly onChange?: (next: ReadonlyArray<string>) => void;
  readonly maxItems?: number;
  readonly maxLength?: number;
  readonly bannedTerm?: string;
}) {
  const { initial = [], suggestions = [], onChange, maxItems, maxLength, bannedTerm } = props;
  const [value, setValue] = useState<ReadonlyArray<string>>(initial);
  return (
    <GlossChipInput
      value={value}
      onChange={(next) => { onChange?.(next); setValue(next); }}
      suggestions={suggestions}
      ariaLabel="Sens cibles"
      label="Sens cibles"
      placeholder="…"
      maxItems={maxItems}
      maxLength={maxLength}
      bannedTerm={bannedTerm}
    />
  );
}

describe('GlossChipInput', () => {
  it('renders the combobox + listbox roles', () => {
    render(<ControlledHarness suggestions={[]} />);
    expect(screen.getByRole('combobox', { name: 'Sens cibles' })).toBeInTheDocument();
  });

  it('Enter commits the typed value as a chip', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness onChange={onChange} />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.change(input, { target: { value: 'animal félin' } });
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(onChange).toHaveBeenCalledWith(['animal félin']);
    expect(screen.getByText('animal félin')).toBeInTheDocument();
    expect(input.value).toBe('');
  });

  it('Comma also commits the chip', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness onChange={onChange} />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.change(input, { target: { value: 'félin' } });
      fireEvent.keyDown(input, { key: ',' });
    });
    expect(onChange).toHaveBeenCalledWith(['félin']);
  });

  it('Backspace on empty input removes the last chip', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness initial={['a', 'b']} onChange={onChange} />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.keyDown(input, { key: 'Backspace' });
    });
    expect(onChange).toHaveBeenLastCalledWith(['a']);
  });

  it('explicit × button on a chip removes it', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness initial={['a', 'b']} onChange={onChange} />);
    const removeBtn = screen.getByRole('button', { name: 'Retirer a' });
    await act(async () => {
      fireEvent.click(removeBtn);
    });
    expect(onChange).toHaveBeenLastCalledWith(['b']);
  });

  it('dedupes via soft normalization (L’animal félin ≡ animal felin)', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness initial={['L’animal félin']} onChange={onChange} />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.change(input, { target: { value: 'animal felin' } });
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    // Only the original chip remains.
    expect(screen.getAllByText(/animal/).length).toBe(1);
    expect(onChange).not.toHaveBeenCalledWith(expect.arrayContaining(['animal felin']));
  });

  it('filters suggestions by normalized substring match', async () => {
    render(<ControlledHarness suggestions={['animal félin', 'conversation digitale', 'félidé']} />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.focus(input);
      fireEvent.change(input, { target: { value: 'feli' } });
    });
    const listbox = screen.getByRole('listbox');
    expect(listbox.textContent).toContain('animal félin');
    expect(listbox.textContent).toContain('félidé');
    expect(listbox.textContent).not.toContain('conversation digitale');
  });

  it('ArrowDown + Enter picks the active suggestion', async () => {
    const onChange = vi.fn();
    render(
      <ControlledHarness
        suggestions={['animal félin', 'conversation digitale']}
        onChange={onChange}
      />,
    );
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => { fireEvent.focus(input); });
    await act(async () => { fireEvent.keyDown(input, { key: 'ArrowDown' }); });
    await act(async () => { fireEvent.keyDown(input, { key: 'Enter' }); });
    expect(onChange).toHaveBeenCalledWith(['conversation digitale']);
  });

  it('blocks add past maxItems', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness initial={['a', 'b']} maxItems={2} onChange={onChange} />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    expect(input.disabled).toBe(true);
  });

  it('rejects a gloss that contains the bannedTerm (ADR-0061 lemma-repetition rule)', async () => {
    const onChange = vi.fn();
    render(<ControlledHarness onChange={onChange} bannedTerm="chat" />);
    const input = screen.getByRole('combobox', { name: 'Sens cibles' }) as HTMLInputElement;
    await act(async () => {
      fireEvent.change(input, { target: { value: 'chat animal félin' } });
      fireEvent.keyDown(input, { key: 'Enter' });
    });
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.queryByText(/chat animal/)).not.toBeInTheDocument();
  });
});
