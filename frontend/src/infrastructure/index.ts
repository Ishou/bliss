// Frontend infrastructure layer. Per ADR-0002 §7, the only place allowed
// to import generated API clients, storage, and telemetry adapters.
//
// The Grid API client is the first such adapter. It is contract-typed against
// `grid/api/openapi.yaml` (ADR-0003) and exported here so that
// the composition root and application use-cases can depend on the
// adapters without reaching into the `api/grid/` subtree directly.

export {
  createGridApiClient,
  type GridApiClient,
  type GridApiClientOptions,
} from './api/grid/client';
export {
  createHttpPuzzleRepository,
  type HttpPuzzleRepositoryOptions,
} from './api/grid/HttpPuzzleRepository';
export {
  createHttpPuzzleSolver,
  type HttpPuzzleSolverOptions,
} from './api/grid/HttpPuzzleSolver';
export {
  createGameApiClient,
  type GameApiClient,
  type GameApiClientOptions,
} from './api/game/client';
export {
  createHttpLobbyClient,
  type HttpLobbyClientOptions,
} from './game/HttpLobbyClient';
export {
  createWebSocketGameClient,
  type WebSocketGameClientOptions,
} from './game/WebSocketGameClient';
export {
  createReconnectingGameClient,
  type ReconnectingGameClientOptions,
} from './game/ReconnectingGameClient';
export {
  createIdentityApiClient,
  type IdentityApiClient,
  type IdentityApiClientOptions,
} from './api/identity/client';
export {
  createHttpAuthClient,
  type HttpAuthClientOptions,
} from './auth/HttpAuthClient';
export {
  createHttpSurveyClient,
  SignInRequiredError,
  CorrectifRejectedError,
  AlreadyRatedError,
  type HttpSurveyClientOptions,
} from './api/survey/client';
export {
  surveyAnonRatedStore,
  SURVEY_ANON_RATED_STORE_KEY,
  type SurveyAnonRatedStore,
} from './session/localStorageSurveyAnon';
