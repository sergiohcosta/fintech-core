import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter } from '@angular/material/core';
import { toSignal } from '@angular/core/rxjs-interop';

import {
  RecurrenceRuleCreateDTO,
  RecurrenceRulePatchDTO,
  RecurrenceRuleResponseDTO,
} from '../../../core/api/fintechSaaSAPI.schemas';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';

@Component({
  selector: 'app-recurring-item-form',
  standalone: true,
  providers: [provideNativeDateAdapter()],
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatDatepickerModule,
  ],
  templateUrl: './recurring-item-form.html',
})
export class RecurringItemFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<RecurringItemFormComponent>);
  private readonly accountsService = inject(AccountsService);
  private readonly categoriesService = inject(CategoriesService);

  readonly existing: RecurrenceRuleResponseDTO | null = inject(MAT_DIALOG_DATA, { optional: true });

  readonly accounts = toSignal(this.accountsService.listAccounts(), { initialValue: [] });
  readonly categories = toSignal(this.categoriesService.listCategories(), { initialValue: [] });

  /** Em modo de edição, só description e baseAmount são editáveis (escopo do PATCH). */
  readonly isEditMode = !!this.existing;

  readonly form = this.fb.group({
    description: [this.existing?.description ?? '', Validators.required],
    baseAmount: [this.existing?.baseAmount ?? null as number | null,
      [Validators.required, Validators.min(0.01)]],
    type: [{ value: this.existing?.type ?? 'EXPENSE', disabled: this.isEditMode }, Validators.required],
    dayOfMonth: [
      { value: this.dayFromRrule(this.existing?.rrule), disabled: this.isEditMode },
      [Validators.required, Validators.min(1), Validators.max(28)]
    ],
    accountId: [
      { value: this.existing?.accountId ?? null as string | null, disabled: this.isEditMode },
      Validators.required
    ],
    categoryId: [
      { value: this.existing?.categoryId ?? null as string | null, disabled: this.isEditMode }
    ],
    startDate: [
      { value: this.defaultStartDate(), disabled: this.isEditMode },
      Validators.required
    ],
  });

  onSubmit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();

    if (this.isEditMode) {
      const patch: RecurrenceRulePatchDTO = {
        description: v.description ?? undefined,
        baseAmount: v.baseAmount ?? undefined,
      };
      this.dialogRef.close(patch);
    } else {
      const create: RecurrenceRuleCreateDTO = {
        description: v.description!,
        baseAmount: v.baseAmount!,
        type: v.type as 'INCOME' | 'EXPENSE',
        rrule: `FREQ=MONTHLY;BYMONTHDAY=${v.dayOfMonth}`,
        accountId: v.accountId!,
        categoryId: v.categoryId ?? undefined,
        startDate: v.startDate
          ? new Date(v.startDate).toISOString().split('T')[0]
          : this.defaultStartDate(),
      };
      this.dialogRef.close(create);
    }
  }

  onCancel(): void { this.dialogRef.close(); }

  private dayFromRrule(rrule?: string | null): number {
    if (!rrule) return 1;
    const match = rrule.match(/BYMONTHDAY=(\d+)/);
    return match ? parseInt(match[1], 10) : 1;
  }

  private defaultStartDate(): string {
    const next = new Date();
    next.setMonth(next.getMonth() + 1, 1);
    return next.toISOString().split('T')[0];
  }
}
