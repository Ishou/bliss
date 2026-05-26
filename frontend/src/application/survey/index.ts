// Application layer barrel for the /sondage surface. Routes import the
// SurveyClient port + the domain-ish types from here; concrete adapters
// (`HttpSurveyClient`) are wired by the composition root and reach the
// route via TanStack Router context, never via a direct import.

export type {
  LikertScore,
  RatingResult,
  RatingSubmission,
  SubmittedAs,
  SurveyCategorie,
  SurveyClient,
  SurveyContribution,
  SurveyCorrectif,
  SurveyFlagReason,
  SurveyItem,
  SurveyPos,
  SurveyPreferencesPatch,
  SurveyProgress,
  SurveyStyle,
  SurveyTier,
} from './types';
