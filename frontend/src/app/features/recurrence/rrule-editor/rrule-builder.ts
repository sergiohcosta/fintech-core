// Lógica pura (sem imports Angular) — testável no Vitest sem TestBed.
// Monta/parseia a string RRULE diretamente: previsível e alinhada ao subconjunto que o
// backend aceita (FREQ MONTHLY/YEARLY, INTERVAL, BYMONTHDAY incl. -1, UNTIL, COUNT).

export type RruleEnd =
  | { kind: 'NEVER' }
  | { kind: 'UNTIL'; date: string } // ISO yyyy-mm-dd
  | { kind: 'COUNT'; count: number };

export interface RruleForm {
  frequency: 'MONTHLY' | 'YEARLY';
  interval: number;
  dayOfMonth: number | 'LAST';
  end: RruleEnd;
}

export function buildRrule(form: RruleForm): string {
  const parts: string[] = [`FREQ=${form.frequency}`];
  if (form.interval > 1) parts.push(`INTERVAL=${form.interval}`);
  parts.push(`BYMONTHDAY=${form.dayOfMonth === 'LAST' ? -1 : form.dayOfMonth}`);
  if (form.end.kind === 'COUNT') parts.push(`COUNT=${form.end.count}`);
  if (form.end.kind === 'UNTIL') parts.push(`UNTIL=${form.end.date.replace(/-/g, '')}T000000Z`);
  return parts.join(';');
}

export function parseRrule(rrule: string): RruleForm {
  const map = new Map(
    rrule.split(';').map((p) => {
      const [k, v] = p.split('=');
      return [k, v] as const;
    }),
  );
  const byday = Number(map.get('BYMONTHDAY'));
  let end: RruleEnd = { kind: 'NEVER' };
  if (map.has('COUNT')) end = { kind: 'COUNT', count: Number(map.get('COUNT')) };
  else if (map.has('UNTIL')) {
    const u = map.get('UNTIL')!; // yyyymmddT...
    end = { kind: 'UNTIL', date: `${u.slice(0, 4)}-${u.slice(4, 6)}-${u.slice(6, 8)}` };
  }
  return {
    frequency: (map.get('FREQ') as 'MONTHLY' | 'YEARLY') ?? 'MONTHLY',
    interval: map.has('INTERVAL') ? Number(map.get('INTERVAL')) : 1,
    dayOfMonth: byday === -1 ? 'LAST' : byday,
    end,
  };
}
