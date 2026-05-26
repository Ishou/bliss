import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { CorrectifField } from '@/ui/components/sondage';

describe('CorrectifField', () => {
  it('renders the text input + style select with the privacy notice', () => {
    render(<CorrectifField value={undefined} onChange={() => {}} />);
    expect(screen.getByLabelText(/Définition alternative/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Style proposé/i)).toBeInTheDocument();
    expect(
      screen.getByText(/Les corrections proposées rejoignent notre corpus/i),
    ).toBeInTheDocument();
  });

  it('reports a SurveyCorrectif on text input change', () => {
    const onChange = vi.fn();
    render(<CorrectifField value={undefined} onChange={onChange} />);
    const input = screen.getByLabelText(/Définition alternative/i);
    fireEvent.input(input, { target: { value: 'meilleure définition' } });
    expect(onChange).toHaveBeenLastCalledWith({
      text: 'meilleure définition',
      style: 'definition_directe',
    });
  });

  it('reports undefined when the trimmed text is empty', () => {
    const onChange = vi.fn();
    render(<CorrectifField value={{ text: 'x', style: 'periphrase' }} onChange={onChange} />);
    const input = screen.getByLabelText(/Définition alternative/i);
    fireEvent.input(input, { target: { value: '   ' } });
    expect(onChange).toHaveBeenLastCalledWith(undefined);
  });

  it('updates the style and re-broadcasts with the current text', () => {
    const onChange = vi.fn();
    render(<CorrectifField value={undefined} onChange={onChange} />);
    const input = screen.getByLabelText(/Définition alternative/i);
    fireEvent.input(input, { target: { value: 'ok' } });
    fireEvent.change(screen.getByLabelText(/Style proposé/i), {
      target: { value: 'periphrase' },
    });
    expect(onChange).toHaveBeenLastCalledWith({ text: 'ok', style: 'periphrase' });
  });
});
