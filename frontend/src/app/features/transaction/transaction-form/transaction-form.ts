import { Component, inject, OnInit, signal, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDatepicker, MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoryResponseDTO, AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';

interface TransactionCategoryOption {
  id: string;
  name: string;
  level: number;
}

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
  private transactionService = inject(TransactionsService);
  private categoryService = inject(CategoriesService);
  private accountService = inject(AccountsService);
  private snackBar = inject(MatSnackBar);

  @ViewChild('picker') private picker!: MatDatepicker<Date>;

  saving = signal(false);
  isEditMode = signal(false);
  transactionId = signal<string | null>(null);
  categories = signal<TransactionCategoryOption[]>([]);
  accounts = signal<AccountResponse[]>([]);
  amountDisplay = signal('');

  form = this.fb.group({
    description: ['', [Validators.required, Validators.minLength(2)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    date: [new Date(), Validators.required],
    type: ['EXPENSE', Validators.required],
    status: ['PENDING'],
    totalInstallments: [1, [Validators.min(1), Validators.max(48)]],
    categoryId: [null as string | null],
    accountId: [null as string | null, Validators.required]
  });

  ngOnInit(): void {
    this.categoryService.listCategories().subscribe({
      next: (data) => this.categories.set(this.flattenCategories(data)),
      error: () => {}
    });
    this.accountService.listAccounts().subscribe({
      next: (data) => this.accounts.set(data),
      error: () => {}
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.transactionId.set(id);
      this.transactionService.getTransaction(id).subscribe({
        next: (t) => {
          this.form.patchValue({
            description: t.description,
            amount: t.amount,
            // O backend retorna string 'YYYY-MM-DD'; o datepicker precisa de Date
            date: new Date(t.date + 'T00:00:00'),
            type: t.type,
            status: t.status,
            categoryId: t.categoryId ?? null,
            accountId: t.accountId ?? null
          });
          this.amountDisplay.set(this.formatAmount(t.amount));
        },
        error: () => {
          this.snackBar.open('Transação não encontrada.', 'Fechar', { duration: 5000 });
          this.router.navigate(['/transactions']);
        }
      });
    }
  }

  // --- Máscara de moeda (#17) ---

  private formatAmount(value: number): string {
    return new Intl.NumberFormat('pt-BR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(value);
  }

  onAmountInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    // Mantém apenas dígitos e vírgula; converte para ponto-flutuante
    const cleaned = input.value.replace(/[^\d,]/g, '');
    const asFloat = parseFloat(cleaned.replace(',', '.'));
    this.form.controls.amount.setValue(isNaN(asFloat) ? null : asFloat);
    this.form.controls.amount.markAsTouched();
  }

  onAmountBlur(event: Event): void {
    const val = this.form.controls.amount.value;
    if (val !== null && val !== undefined) {
      const input = event.target as HTMLInputElement;
      const formatted = this.formatAmount(val);
      input.value = formatted;
      this.amountDisplay.set(formatted);
    }
  }

  onAmountFocus(event: Event): void {
    const val = this.form.controls.amount.value;
    const input = event.target as HTMLInputElement;
    input.value = val !== null && val !== undefined ? String(val).replace('.', ',') : '';
  }

  // --- Máscara de data (#18) ---

  onDateKeydown(event: KeyboardEvent): void {
    const PASS_THROUGH = ['Backspace', 'Delete', 'Tab', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter'];
    if (PASS_THROUGH.includes(event.key) || !(/^\d$/.test(event.key))) return;

    const input = event.target as HTMLInputElement;
    const val = input.value;
    // Insere barra automaticamente após o dia (pos 2) e após o mês (pos 5)
    if (val.length === 2 || val.length === 5) {
      input.value = val + '/';
    }
  }

  onDateBlur(event: FocusEvent): void {
    // Se o foco foi para o toggle do datepicker, o calendar está prestes a abrir —
    // chamar setValue aqui causaria um tick de CD durante a inicialização do overlay,
    // deixando o backdrop preso (tela acinzentada/inclicável no modo Zoneless).
    const relatedTarget = event.relatedTarget as HTMLElement | null;
    if (relatedTarget?.closest('mat-datepicker-toggle')) return;
    // Se o calendar já está aberto (ex: foco saiu ao clicar no overlay), também não processar.
    if (this.picker?.opened) return;

    const input = event.target as HTMLInputElement;
    const val = input.value;
    const match = val.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
    if (match) {
      const [, day, month, year] = match;
      const date = new Date(+year, +month - 1, +day);
      if (!isNaN(date.getTime())) {
        this.form.controls.date.setValue(date);
      }
    }
  }

  // --- Utilitários ---

  private flattenCategories(cats: CategoryResponseDTO[], level = 0): TransactionCategoryOption[] {
    return cats.flatMap(c => [
      { id: c.id, name: c.name, level },
      ...this.flattenCategories(c.children ?? [], level + 1)
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
      type: raw.type as 'INCOME' | 'EXPENSE',
      status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
      categoryId: raw.categoryId ?? undefined,
      accountId: raw.accountId!
    };

    if (this.isEditMode()) {
      this.transactionService.updateTransaction(this.transactionId()!, payload).subscribe({
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
      this.transactionService.createTransaction({ ...payload, totalInstallments: raw.totalInstallments ?? 1 }).subscribe({
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
