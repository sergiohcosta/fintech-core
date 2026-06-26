import { Component, computed, effect, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { RRule } from 'rrule';

import { buildRrule, RruleEnd, RruleForm } from './rrule-builder';

/**
 * Editor visual de RRULE (subconjunto financeiro). Emite o `rrule` string a cada mudança
 * e mostra um preview das próximas 3 ocorrências (rrule.js). Estado em signals; a montagem
 * da string é delegada à lógica pura `buildRrule` (testada no Vitest sem TestBed).
 */
@Component({
  selector: 'app-rrule-editor',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatRadioModule,
    MatDatepickerModule,
    MatNativeDateModule,
  ],
  templateUrl: './rrule-editor.html',
  styleUrl: './rrule-editor.scss',
})
export class RruleEditor {
  /** Emitido a cada alteração do formulário, já como string RRULE pronta pro backend. */
  readonly rruleChange = output<string>();

  // Estado do editor (signals-first).
  readonly frequency = signal<'MONTHLY' | 'YEARLY'>('MONTHLY');
  readonly interval = signal(1);
  // 1..28 (dias sempre válidos) + 'LAST' (último dia do mês → BYMONTHDAY=-1).
  readonly dayOfMonth = signal<number | 'LAST'>(1);
  readonly endKind = signal<'NEVER' | 'UNTIL' | 'COUNT'>('NEVER');
  readonly untilDate = signal<string>('');
  readonly count = signal<number>(12);

  readonly days = Array.from({ length: 28 }, (_, i) => i + 1);

  readonly form = computed<RruleForm>(() => ({
    frequency: this.frequency(),
    interval: this.interval(),
    dayOfMonth: this.dayOfMonth(),
    end: this.buildEnd(),
  }));

  readonly rrule = computed(() => buildRrule(this.form()));

  // Próximas 3 ocorrências a partir de hoje — preview puramente ilustrativo.
  readonly preview = computed<Date[]>(() => {
    try {
      const opts = RRule.parseString(this.rrule());
      opts.dtstart = new Date();
      return new RRule(opts).all((_, i) => i < 3);
    } catch {
      return [];
    }
  });

  constructor() {
    // Propaga a string a cada mudança (zoneless: effect roda fora de zone.js).
    effect(() => this.rruleChange.emit(this.rrule()));
  }

  private buildEnd(): RruleEnd {
    switch (this.endKind()) {
      case 'UNTIL':
        return { kind: 'UNTIL', date: this.untilDate() };
      case 'COUNT':
        return { kind: 'COUNT', count: this.count() };
      default:
        return { kind: 'NEVER' };
    }
  }
}
