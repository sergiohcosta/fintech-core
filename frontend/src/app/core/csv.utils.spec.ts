import { describe, it, expect } from 'vitest';
import { csvField } from './csv.utils';

describe('csvField', () => {
  it('retorna string vazia para null/undefined', () => {
    expect(csvField(null)).toBe('');
    expect(csvField(undefined)).toBe('');
  });

  it('retorna o valor sem alteração quando não há caracteres especiais', () => {
    expect(csvField('Nubank')).toBe('Nubank');
  });

  it('envolve em aspas duplas quando contém ponto-e-vírgula', () => {
    expect(csvField('A; B')).toBe('"A; B"');
  });

  it('escapa aspas duplas internas duplicando-as', () => {
    expect(csvField('Diz "oi"')).toBe('"Diz ""oi"""');
  });

  it('envolve em aspas duplas quando contém quebra de linha', () => {
    expect(csvField('linha1\nlinha2')).toBe('"linha1\nlinha2"');
  });
});
