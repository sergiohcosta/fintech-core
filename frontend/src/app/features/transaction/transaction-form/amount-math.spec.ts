import { describe, it, expect } from 'vitest';
import { evaluateMathExpression } from './amount-math';

describe('evaluateMathExpression', () => {
  it('soma simples', () => expect(evaluateMathExpression('300+200')).toBe(500));
  it('subtração', () => expect(evaluateMathExpression('500-150')).toBe(350));
  it('multiplicação', () => expect(evaluateMathExpression('12*5')).toBe(60));
  it('divisão', () => expect(evaluateMathExpression('100/4')).toBe(25));
  it('potência', () => expect(evaluateMathExpression('2^10')).toBe(1024));
  it('potência associa à direita: 2^3^2 = 2^9', () => expect(evaluateMathExpression('2^3^2')).toBe(512));
  it('parênteses alteram precedência', () => expect(evaluateMathExpression('(2+3)*4')).toBe(20));
  it('vírgula como separador decimal', () => expect(evaluateMathExpression('1,5+1,5')).toBe(3));
  it('ponto como separador decimal', () => expect(evaluateMathExpression('1.5+1.5')).toBe(3));
  it('expressão com espaços', () => expect(evaluateMathExpression('100 + 50')).toBe(150));
  it('número sozinho', () => expect(evaluateMathExpression('42')).toBe(42));
  it('expressão inválida retorna null', () => expect(evaluateMathExpression('abc')).toBeNull());
  it('string vazia retorna null', () => expect(evaluateMathExpression('')).toBeNull());
  it('divisão por zero retorna null', () => expect(evaluateMathExpression('10/0')).toBeNull());
  it('parênteses não fechados retorna null', () => expect(evaluateMathExpression('(10+5')).toBeNull());
  it('caracteres sobrando retorna null', () => expect(evaluateMathExpression('10+5x')).toBeNull());
});
