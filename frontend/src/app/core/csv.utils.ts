// Escapa campo CSV segundo RFC 4180: se contém `;`, `"` ou `\n`, envolve em aspas duplas e duplica aspas internas.
export function csvField(value: string | null | undefined): string {
  const v = value ?? '';
  if (v.includes(';') || v.includes('"') || v.includes('\n')) {
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
