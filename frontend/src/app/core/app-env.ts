/**
 * Config de runtime do frontend — ambiente + versão exibidos na marca d'água.
 *
 * Por que runtime e não build-time: a MESMA imagem Docker roda em dev/hmg/prod
 * (build único → 3 namespaces). O ambiente só é conhecido no deploy, então é
 * lido de `window.__APP_ENV` (arquivo env.js reescrito pelo entrypoint do nginx
 * a partir de env vars injetadas no deploy). O SHA é assado no build; a versão
 * SemVer (quando existe) é injetada no deploy. Racional: ADR-005.
 *
 * Lógica pura, sem imports Angular → testável no Vitest sem TestBed (convenção
 * do projeto).
 */
export interface AppEnv {
  /** Nome do ambiente: local | dev | hmg | prod. */
  environment: string;
  /** SemVer 'vX.Y.Z' quando o SHA rodando == commit taggado; senão vazio. */
  version: string;
  /** Git SHA do build (completo; encurtado só na exibição). */
  sha: string;
}

interface AppEnvCarrier {
  __APP_ENV?: Partial<AppEnv>;
}

// Augmenta o tipo global: env.js define window.__APP_ENV no boot. Sem isto o TS
// estrito não reconhece a propriedade em `window`.
declare global {
  interface Window {
    __APP_ENV?: Partial<AppEnv>;
  }
}

const DEFAULTS: AppEnv = { environment: 'local', version: '', sha: 'dev' };

/** Lê `window.__APP_ENV` com defaults graciosos (campo ausente/vazio → default). */
export function readAppEnv(source: AppEnvCarrier = window): AppEnv {
  const raw = source.__APP_ENV ?? {};
  return {
    environment: raw.environment || DEFAULTS.environment,
    version: raw.version || DEFAULTS.version,
    sha: raw.sha || DEFAULTS.sha,
  };
}

/** SHA curto (7 chars) para exibição. 'dev' já é curto e passa inalterado. */
export function shortSha(sha: string): string {
  return sha.slice(0, 7);
}

/**
 * Rótulo da marca d'água:
 *   com SemVer → 'prod · v0.2.0 (a1b2c3d)'
 *   só SHA     → 'dev · a1b2c3d'
 *   local      → 'local · dev'
 */
export function formatVersionLabel(env: AppEnv): string {
  const sha = shortSha(env.sha);
  const versionPart = env.version ? `${env.version} (${sha})` : sha;
  return `${env.environment} · ${versionPart}`;
}
