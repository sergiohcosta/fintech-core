import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { TransactionService } from '../../../core/services/transaction';
import { CategoryService } from '../../../core/services/category';
import { CreditCardService } from '../../../core/services/credit-card';
import { CategoryModel } from '../../../core/models/category';
import { CreditCardModel } from '../../../core/models/credit-card';

@Component({
  selector: 'app-transaction-form',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatSnackBarModule
  ],
  templateUrl: './transaction-form.html',
  styleUrl: './transaction-form.scss'
})
export class TransactionForm implements OnInit {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private transactionService = inject(TransactionService);
  private categoryService = inject(CategoryService);
  private creditCardService = inject(CreditCardService);
  private snackBar = inject(MatSnackBar);

  saving = signal(false);
  isEditMode = signal(false);
  transactionId = signal<string | null>(null);
  categories = signal<CategoryModel[]>([]);
  creditCards = signal<CreditCardModel[]>([]);

  form = this.fb.group({
    description: ['', [Validators.required, Validators.minLength(2)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    date: [new Date(), Validators.required],
    type: ['EXPENSE', Validators.required],
    status: ['PENDING'],
    totalInstallments: [1, [Validators.min(1), Validators.max(48)]],
    categoryId: [null as string | null],
    creditCardId: [null as string | null]
  });

  ngOnInit(): void {
    this.categoryService.list().subscribe({
      next: (data) => this.categories.set(this.flattenCategories(data)),
      error: () => {}
    });
    this.creditCardService.list().subscribe({
      next: (data) => this.creditCards.set(data),
      error: () => {}
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.transactionId.set(id);
      this.transactionService.getById(id).subscribe({
        next: (t) => {
          this.form.patchValue({
            description: t.description,
            amount: t.amount,
            // O backend retorna string 'YYYY-MM-DD'; o datepicker precisa de Date
            date: new Date(t.date + 'T00:00:00'),
            type: t.type,
            status: t.status,
            categoryId: null,
            creditCardId: null
          });
        },
        error: () => {
          this.snackBar.open('Transação não encontrada.', 'Fechar', { duration: 5000 });
          this.router.navigate(['/transactions']);
        }
      });
    }
  }

  private flattenCategories(cats: CategoryModel[], prefix = ''): CategoryModel[] {
    return cats.flatMap(c => [
      { ...c, name: prefix + c.name },
      ...this.flattenCategories(c.children ?? [], prefix + '  ')
    ]);
  }

  private toDateString(date: Date): string {
    return date.toISOString().split('T')[0];
  }

  onSubmit(): void {
    if (this.form.invalid) return;

    const raw = this.form.getRawValue();
    this.saving.set(true);

    const payload = {
      description: raw.description!,
      amount: raw.amount!,
      date: this.toDateString(raw.date as Date),
      type: raw.type as 'INCOME' | 'EXPENSE' | 'TRANSFER',
      status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
      categoryId: raw.categoryId ?? undefined,
      creditCardId: raw.creditCardId ?? undefined
    };

    if (this.isEditMode()) {
      this.transactionService.update(this.transactionId()!, payload).subscribe({
        next: () => {
          this.snackBar.open('Transação atualizada com sucesso!', 'OK', { duration: 3000 });
          this.router.navigate(['/transactions']);
        },
        error: () => {
          this.saving.set(false);
          this.snackBar.open('Erro ao atualizar transação.', 'Fechar', { duration: 5000 });
        }
      });
    } else {
      this.transactionService.create({ ...payload, totalInstallments: raw.totalInstallments ?? 1 }).subscribe({
        next: (created) => {
          const msg = created.length > 1
            ? `${created.length} parcelas criadas com sucesso!`
            : 'Transação criada com sucesso!';
          this.snackBar.open(msg, 'OK', { duration: 3000 });
          this.router.navigate(['/transactions']);
        },
        error: () => {
          this.saving.set(false);
          this.snackBar.open('Erro ao salvar transação.', 'Fechar', { duration: 5000 });
        }
      });
    }
  }
}
