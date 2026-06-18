import { Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { finalize } from 'rxjs/operators';
import { PlanningService } from '../planning.service';

@Component({
  selector: 'app-planning-settings',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule, MatFormFieldModule, MatInputModule, MatSnackBarModule,
  ],
  template: `
    <div style="padding:24px;max-width:480px">
      <h2>Configurações de planejamento</h2>

      <mat-form-field appearance="outline" style="width:100%">
        <mat-label>Dia de início do ciclo (1–28)</mat-label>
        <input matInput type="number" [formControl]="startDay" min="1" max="28">
        <mat-hint>
          Dia 1 = ciclo coincide com o mês calendário.
          Outro valor: o ciclo começa nesse dia do mês anterior.
        </mat-hint>
        @if (startDay.hasError('min') || startDay.hasError('max')) {
          <mat-error>O dia deve estar entre 1 e 28.</mat-error>
        }
      </mat-form-field>

      <div style="margin-top:24px">
        <button mat-flat-button color="primary"
                [disabled]="startDay.invalid || saving()"
                (click)="save()">
          Salvar
        </button>
      </div>
    </div>
  `,
})
export class PlanningSettingsComponent implements OnInit {
  private readonly planningService = inject(PlanningService);
  private readonly snackBar = inject(MatSnackBar);

  readonly saving = signal(false);
  readonly startDay = new FormControl<number>(1, {
    nonNullable: true,
    validators: [Validators.required, Validators.min(1), Validators.max(28)],
  });

  ngOnInit(): void {
    this.planningService.getSettings().subscribe({
      next: s => this.startDay.setValue(s.budgetCycleStartDay ?? 1),
      error: () => {},
    });
  }

  save(): void {
    if (this.startDay.invalid) return;
    this.saving.set(true);
    this.planningService.patchTenantSettings({ budgetCycleStartDay: this.startDay.value })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => this.snackBar.open('Configurações salvas.', 'OK', { duration: 3000 }),
        error: () => this.snackBar.open('Erro ao salvar.', 'OK', { duration: 3000 }),
      });
  }
}
