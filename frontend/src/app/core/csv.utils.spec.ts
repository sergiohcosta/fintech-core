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

  // #143 — CSV formula injection: valores começando com = + - @ TAB CR são interpretados
  // como fórmula por Excel/LibreOffice. Prefixar apóstrofo força tratamento como texto.
  it('prefixa apóstrofo em valores que começam com caractere de fórmula', () => {
    expect(csvField('+1+1')).toBe("'+1+1");
    expect(csvField('-2+3')).toBe("'-2+3");
    expect(csvField('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvField('\tcmd')).toBe("'\tcmd");
  });

  it('prefixa apóstrofo mesmo quando o payload também exige quoting', () => {
    // =HYPERLINK(...) começa com = (fórmula) e contém aspas (quoting) — ambas defesas aplicadas.
    expect(csvField('=HYPERLINK("http://x","c")')).toContain("'=");
  });

  it('valor com CR (\\r) força quoting', () => {
    expect(csvField('linha1\rlinha2')).toBe('"linha1\rlinha2"');
  });

  it('não neutraliza hífen no meio do texto (dado legítimo preservado)', () => {
    expect(csvField('R$ 50 - ajuste')).toBe('R$ 50 - ajuste');
  });
});
