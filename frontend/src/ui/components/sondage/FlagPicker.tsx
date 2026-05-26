// Flag picker for /sondage — Ark UI Select keeps brand parity with Likert/TextField.

import { Select, type SelectOption } from '@/ui/components/primitives';
import type { SurveyFlagReason } from '@/application/survey';

// Ark rejects empty-string values, so '__none__' stands in for the "no flag" state.
const NONE_VALUE = '__none__';
type FlagOptionValue = SurveyFlagReason | typeof NONE_VALUE;

const OPTIONS: ReadonlyArray<SelectOption<FlagOptionValue>> = [
  { value: NONE_VALUE, label: '— Aucun —' },
  { value: 'hors_sujet', label: 'Hors-sujet' },
  { value: 'auto_reference', label: 'Auto-référence' },
  { value: 'erreur_sens', label: 'Erreur de sens' },
  { value: 'autre', label: 'Autre' },
];

export interface FlagPickerProps {
  readonly value: SurveyFlagReason | undefined;
  readonly onChange: (value: SurveyFlagReason | undefined) => void;
}

export function FlagPicker({ value, onChange }: FlagPickerProps) {
  return (
    <Select<FlagOptionValue>
      label="Signaler un problème (optionnel)"
      value={value ?? NONE_VALUE}
      onValueChange={(next) => {
        if (next == null || next === NONE_VALUE) {
          onChange(undefined);
          return;
        }
        onChange(next);
      }}
      options={OPTIONS}
    />
  );
}
