// Escapa campo CSV segundo RFC 4180 + neutraliza CSV formula injection (#143).
export function csvField(value: string | null | undefined): string {
  let v = value ?? '';
  // #143: se o valor começa com um caractere que a planilha interpreta como fórmula
  // (`= + - @` ou TAB/CR), prefixa apóstrofo para forçar tratamento como texto. O quoting
  // abaixo protege a ESTRUTURA do CSV, mas NÃO impede a execução da fórmula — defesas distintas.
  // Neutraliza na borda (prefixo), sem remover caracteres do dado (ex: "-R$ 50" é legítimo).
  if (/^[=+\-@\t\r]/.test(v)) {
    v = "'" + v;
  }
  if (v.includes(';') || v.includes('"') || v.includes('\n') || v.includes('\r')) {
    return '"' + v.replace(/"/g, '""') + '"';
  }
  return v;
}

export function triggerCsvDownload(csv: string, filename: string): void {
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url  = URL.createObjectURL(blob);
  const a    = Object.assign(document.createElement('a'), { href: url, download: filename });
  a.click();
  URL.revokeObjectURL(url);
}
