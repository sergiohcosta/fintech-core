import { describe, it, expect } from 'vitest';
import { readAppEnv, formatVersionLabel, shortSha } from './app-env';

describe('app-env', () => {
  describe('readAppEnv', () => {
    it('aplica defaults quando __APP_ENV ausente', () => {
      expect(readAppEnv({})).toEqual({ environment: 'local', version: '', sha: 'dev' });
    });

    it('aplica defaults campo a campo (vazio → default)', () => {
      const env = readAppEnv({ __APP_ENV: { environment: 'prod', version: '', sha: '' } });
      expect(env).toEqual({ environment: 'prod', version: '', sha: 'dev' });
    });

    it('lê valores injetados', () => {
      const env = readAppEnv({ __APP_ENV: { environment: 'hmg', version: 'v0.2.0', sha: 'abc123def' } });
      expect(env).toEqual({ environment: 'hmg', version: 'v0.2.0', sha: 'abc123def' });
    });
  });

  it('shortSha corta em 7 chars; "dev" passa inalterado', () => {
    expect(shortSha('a1b2c3d4e5f6')).toBe('a1b2c3d');
    expect(shortSha('dev')).toBe('dev');
  });

  describe('formatVersionLabel', () => {
    it('com SemVer: env · vX.Y.Z (sha)', () => {
      expect(formatVersionLabel({ environment: 'prod', version: 'v0.2.0', sha: 'a1b2c3d4e5' })).toBe(
        'prod · v0.2.0 (a1b2c3d)',
      );
    });

    it('sem SemVer: env · sha', () => {
      expect(formatVersionLabel({ environment: 'dev', version: '', sha: 'a1b2c3d4e5' })).toBe(
        'dev · a1b2c3d',
      );
    });

    it('local: env · dev', () => {
      expect(formatVersionLabel({ environment: 'local', version: '', sha: 'dev' })).toBe('local · dev');
    });
  });
});
