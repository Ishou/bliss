// Frontend infrastructure layer. Per ADR-0002 §7, the only place allowed
// to import generated API clients, storage, and telemetry adapters.
//
// The Grid API client is the first such adapter. It is contract-typed against
// `grid/api/openapi.yaml` (ADR-0003) and exported here so that
// `application/` use-cases can depend on the factory without reaching into
// the `api/grid/` subtree directly.

export {
  createGridApiClient,
  type GridApiClient,
  type GridApiClientOptions,
} from './api/grid/client';
