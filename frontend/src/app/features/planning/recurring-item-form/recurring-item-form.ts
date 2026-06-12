import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RecurringBudgetItemRequest, RecurringBudgetItemResponse } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-recurring-item-form',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule,
  ],
  templateUrl: './recurring-item-form.html',
})
export class RecurringItemFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<RecurringItemFormComponent>);
  readonly existing: RecurringBudgetItemResponse | null = inject(MAT_DIALOG_DATA, { optional: true });

  readonly form = this.fb.group({
    description: [this.existing?.description ?? '', Validators.required],
    amount: [this.existing?.amount ?? null as number | null, [Validators.required, Validators.min(0.01)]],
    type: [this.existing?.type ?? 'EXPENSE', Validators.required],
    dayOfMonth: [this.existing?.dayOfMonth ?? 1, [Validators.required, Validators.min(1), Validators.max(28)]],
  });

  onSubmit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const result: RecurringBudgetItemRequest = {
      description: v.description!,
      amount: v.amount!,
      type: v.type as 'INCOME' | 'EXPENSE',
      dayOfMonth: v.dayOfMonth!,
    };
    this.dialogRef.close(result);
  }

  onCancel(): void { this.dialogRef.close(); }
}
