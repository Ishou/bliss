// /compte section for deleteProposedOnErasure preference — disables during PATCH.

import { useState } from 'react';
import { css } from 'styled-system/css';
import type { SurveyClient } from '@/application/survey';

const fieldsetStyles = css({
  border: 'none',
  padding: 0,
  margin: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const legendStyles = css({
  fontSize: 'body',
  fontWeight: 'semibold',
  color: 'fg',
});

const rowStyles = css({
  display: 'flex',
  alignItems: 'flex-start',
  gap: 'sm',
  fontSize: 'body',
  color: 'fg',
});

const statusStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
});

const alertStyles = css({
  fontSize: 'sm',
  color: 'errorText',
  margin: 0,
});

export interface SurveyPreferencesProps {
  readonly surveyClient: SurveyClient;
  // Hydrate from server state when GET /me/preferences ships; v1 defaults to false.
  readonly initialDeleteOnErasure?: boolean;
}

export function SurveyPreferences({
  surveyClient,
  initialDeleteOnErasure = false,
}: SurveyPreferencesProps) {
  const [deleteOnErasure, setDeleteOnErasure] = useState(initialDeleteOnErasure);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<string | null>(null);

  async function save(next: boolean): Promise<void> {
    setSaving(true);
    setError(null);
    setDeleteOnErasure(next);
    try {
      await surveyClient.patchPreferences({ deleteProposedOnErasure: next });
      setSavedAt('Enregistré.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
      // Roll back the optimistic UI.
      setDeleteOnErasure(!next);
    } finally {
      setSaving(false);
    }
  }

  return (
    <fieldset className={fieldsetStyles}>
      <legend className={legendStyles}>Confidentialité du sondage</legend>
      <label className={rowStyles}>
        <input
          type="checkbox"
          checked={deleteOnErasure}
          disabled={saving}
          onChange={(event) => { void save(event.target.checked); }}
        />
        <span>
          Supprimer aussi mes corrections proposées en cas de suppression de mon compte.
        </span>
      </label>
      {error !== null ? (
        <p className={alertStyles} role="alert">Impossible d&apos;enregistrer : {error}</p>
      ) : savedAt !== null && !saving ? (
        <p className={statusStyles} role="status">{savedAt}</p>
      ) : null}
    </fieldset>
  );
}
