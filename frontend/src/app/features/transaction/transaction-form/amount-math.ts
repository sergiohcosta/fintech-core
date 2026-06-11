/**
 * Avalia uma expressão matemática simples com as 4 operações e potência.
 * Implementado como parser recursivo descendente — sem eval, seguro para input do usuário.
 *
 * Gramática:
 *   expr   = term  (('+' | '-') term)*
 *   term   = power (('*' | '/') power)*
 *   power  = base  ('^' power)*          // associatividade direita: 2^3^2 = 2^(3^2)
 *   base   = number | '(' expr ')'
 *   number = dígitos com separador decimal '.' ou ','
 */
export function evaluateMathExpression(input: string): number | null {
  const s = input.replace(/\s/g, '').replace(/,/g, '.');
  if (!s) return null;

  let pos = 0;

  function parseExpr(): number {
    let left = parseTerm();
    while (pos < s.length && (s[pos] === '+' || s[pos] === '-')) {
      const op = s[pos++];
      const right = parseTerm();
      left = op === '+' ? left + right : left - right;
    }
    return left;
  }

  function parseTerm(): number {
    let left = parsePower();
    while (pos < s.length && (s[pos] === '*' || s[pos] === '/')) {
      const op = s[pos++];
      const right = parsePower();
      if (op === '/' && right === 0) throw new Error('division by zero');
      left = op === '*' ? left * right : left / right;
    }
    return left;
  }

  function parsePower(): number {
    const base = parseBase();
    if (pos < s.length && s[pos] === '^') {
      pos++;
      const exp = parsePower();
      return Math.pow(base, exp);
    }
    return base;
  }

  function parseBase(): number {
    if (s[pos] === '(') {
      pos++;
      const val = parseExpr();
      if (s[pos] !== ')') throw new Error('expected )');
      pos++;
      return val;
    }
    const m = s.slice(pos).match(/^\d+(\.\d+)?/);
    if (!m) throw new Error(`expected number at ${pos}`);
    pos += m[0].length;
    return parseFloat(m[0]);
  }

  try {
    const result = parseExpr();
    if (pos !== s.length) return null; // sobraram caracteres não consumidos
    return isFinite(result) ? result : null;
  } catch {
    return null;
  }
}
