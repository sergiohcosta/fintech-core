import { describe, it, expect } from 'vitest';
import { readAppEnv, formatVersionLabel, formatVersionTooltip, shortSha } from './app-env';

describe('app-env', () => {
  describe('readAppEnv', () => {
    it('aplica defaults quando __APP_ENV ausente', () => {
      expect(readAppEnv({})).toEqual({ environment: 'local', version: '', sha: 'dev', commitTime: '' });
    });

    it('aplica defaults campo a campo (vazio → default)', () => {
      const env = readAppEnv({ __APP_ENV: { environment: 'prod', version: '', sha: '', commitTime: '' } });
      expect(env).toEqual({ environment: 'prod', version: '', sha: 'dev', commitTime: '' });
    });

    it('lê valores injetados', () => {
      const env = readAppEnv({
        __APP_ENV: {
          environment: 'hmg',
          version: 'v0.2.0',
          sha: 'abc123def',
          commitTime: '2026-08-06T20:35:39Z',
        },
      });
      expect(env).toEqual({
        environment: 'hmg',
        version: 'v0.2.0',
        sha: 'abc123def',
        commitTime: '2026-08-06T20:35:39Z',
      });
    });
  });

  it('shortSha corta em 7 chars; "dev" passa inalterado', () => {
    expect(shortSha('a1b2c3d4e5f6')).toBe('a1b2c3d');
    expect(shortSha('dev')).toBe('dev');
  });

  describe('formatVersionLabel', () => {
    it('com SemVer: env · vX.Y.Z (sha)', () => {
      expect(
        formatVersionLabel({ environment: 'prod', version: 'v0.2.0', sha: 'a1b2c3d4e5', commitTime: '' }),
      ).toBe('prod · v0.2.0 (a1b2c3d)');
    });

    it('sem SemVer: env · sha', () => {
      expect(
        formatVersionLabel({ environment: 'dev', version: '', sha: 'a1b2c3d4e5', commitTime: '' }),
      ).toBe('dev · a1b2c3d');
    });

    it('local: env · dev', () => {
      expect(formatVersionLabel({ environment: 'local', version: '', sha: 'dev', commitTime: '' })).toBe(
        'local · dev',
      );
    });
  });

  describe('formatVersionTooltip', () => {
    it('com commitTime válido: label seguido de data/hora DD/MM/YYYY HH:mm', () => {
      const tooltip = formatVersionTooltip({
        environment: 'prod',
        version: 'v0.5.0',
        sha: 'a1b2c3d4e5',
        commitTime: '2026-08-06T20:35:39Z',
      });
      expect(tooltip).toMatch(/^prod · v0\.5\.0 \(a1b2c3d\) · \d{2}\/\d{2}\/\d{4}.*\d{2}:\d{2}$/);
    });

    it('sem commitTime: só o label (sem sufixo de data)', () => {
      expect(
        formatVersionTooltip({ environment: 'dev', version: '', sha: 'a1b2c3d4e5', commitTime: '' }),
      ).toBe('dev · a1b2c3d');
    });

    it('commitTime inválido (não parseável): degrada pro label, sem quebrar', () => {
      expect(
        formatVersionTooltip({
          environment: 'dev',
          version: '',
          sha: 'a1b2c3d4e5',
          commitTime: 'not-a-date',
        }),
      ).toBe('dev · a1b2c3d');
    });
  });
});
