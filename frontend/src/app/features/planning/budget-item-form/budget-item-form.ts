import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatNativeDateModule } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { BudgetCycleOpenRequest, BudgetItemCreateRequest } from '../../../core/api/fintechSaaSAPI.schemas';

export type BudgetItemFormResult = BudgetItemCreateRequest;

export interface BudgetItemFormData {
  cycleId?: string;
  mode?: 'openCycle';
}

@Component({
  selector: 'app-budget-item-form',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatDatepickerModule, MatNativeDateModule, MatDialogModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
  ],
  templateUrl: './budget-item-form.html',
})
export class BudgetItemFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<BudgetItemFormComponent>);
  readonly data: BudgetItemFormData = inject(MAT_DIALOG_DATA);

  readonly isOpenCycleMode = signal(false);

  readonly cycleForm = this.fb.group({
    referenceMonth: ['', [Validators.required, Validators.pattern(/^\d{4}-\d{2}$/)]],
    startDay: [1, [Validators.required, Validators.min(1), Validators.max(28)]],
  });

  readonly itemForm = this.fb.group({
    description: ['', Validators.required],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    type: ['EXPENSE', Validators.required],
    expectedDate: [null as Date | null, Validators.required],
  });

  ngOnInit(): void {
    this.isOpenCycleMode.set(this.data?.mode === 'openCycle');
  }

  onSubmit(): void {
    if (this.isOpenCycleMode()) {
      if (this.cycleForm.invalid) return;
      const v = this.cycleForm.getRawValue();
      this.dialogRef.close({ referenceMonth: v.referenceMonth!, startDay: v.startDay! });
    } else {
      if (this.itemForm.invalid) return;
      const v = this.itemForm.getRawValue();
      const result: BudgetItemCreateRequest = {
        description: v.description!,
        amount: v.amount!,
        type: v.type as 'INCOME' | 'EXPENSE',
        expectedDate: v.expectedDate!.toISOString().substring(0, 10),
      };
      this.dialogRef.close(result);
    }
  }

  onCancel(): void {
    this.dialogRef.close();
  }
}
