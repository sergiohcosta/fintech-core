import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
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
  private transactionService = inject(TransactionService);
  private categoryService = inject(CategoryService);
  private creditCardService = inject(CreditCardService);
  private snackBar = inject(MatSnackBar);

  saving = signal(false);
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
  }

  // Achata a árvore de categorias para exibição no select
  private flattenCategories(cats: CategoryModel[], prefix = ''): CategoryModel[] {
    return cats.flatMap(c => [
      { ...c, name: prefix + c.name },
      ...this.flattenCategories(c.children ?? [], prefix + '  ')
    ]);
  }

  onSubmit(): void {
    if (this.form.invalid) return;

    const raw = this.form.getRawValue();
    this.saving.set(true);

    this.transactionService.create({
      description: raw.description!,
      amount: raw.amount!,
      // Converte Date do datepicker para string ISO (YYYY-MM-DD) esperada pelo backend
      date: (raw.date as Date).toISOString().split('T')[0],
      type: raw.type as 'INCOME' | 'EXPENSE' | 'TRANSFER',
      status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
      totalInstallments: raw.totalInstallments ?? 1,
      categoryId: raw.categoryId ?? undefined,
      creditCardId: raw.creditCardId ?? undefined
    }).subscribe({
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
