import { describe, it, expect } from 'vitest';
import { getSuggestion, TAXONOMY_ROOTS } from './taxonomy-suggestions';

describe('getSuggestion', () => {
  it('retorna código para nome exato em PT-BR', () => {
    expect(getSuggestion('Moradia')).toBe('HOUSING');
    expect(getSuggestion('Alimentação')).toBe('FOOD');
    expect(getSuggestion('Transporte')).toBe('TRANSPORT');
    expect(getSuggestion('Pets')).toBe('PETS');
  });

  it('é case-insensitive', () => {
    expect(getSuggestion('MORADIA')).toBe('HOUSING');
    expect(getSuggestion('moradia')).toBe('HOUSING');
    expect(getSuggestion('Moradia')).toBe('HOUSING');
  });

  it('ignora acentos ao comparar', () => {
    expect(getSuggestion('Saude')).toBe('HEALTH');
    expect(getSuggestion('Saúde')).toBe('HEALTH');
    expect(getSuggestion('Educacao')).toBe('EDUCATION');
    expect(getSuggestion('Educação')).toBe('EDUCATION');
    expect(getSuggestion('Financas')).toBe('FINANCIAL');
    expect(getSuggestion('Finanças')).toBe('FINANCIAL');
  });

  it('retorna null para nome sem correspondência', () => {
    expect(getSuggestion('Roupas & Casa')).toBeNull();
    expect(getSuggestion('Desconhecido')).toBeNull();
  });

  it('retorna null para string vazia', () => {
    expect(getSuggestion('')).toBeNull();
  });
});

describe('TAXONOMY_ROOTS', () => {
  it('tem exatamente 14 entradas', () => {
    expect(TAXONOMY_ROOTS).toHaveLength(14);
  });

  it('cada entrada tem code, label e icon preenchidos', () => {
    for (const root of TAXONOMY_ROOTS) {
      expect(root.code).toBeTruthy();
      expect(root.label).toBeTruthy();
      expect(root.icon).toBeTruthy();
    }
  });

  it('todos os codes são únicos', () => {
    const codes = TAXONOMY_ROOTS.map(r => r.code);
    expect(new Set(codes).size).toBe(14);
  });
});
