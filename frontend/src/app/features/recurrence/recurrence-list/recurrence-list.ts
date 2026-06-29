import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RruleEditor } from '../rrule-editor/rrule-editor';
import { describeRrule } from '../rrule-editor/rrule-builder';
import { RecurrenceService } from '../../../core/services/recurrence.service';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import {
  AccountResponse,
  CategoryResponseDTO,
  RecurrenceRuleResponseDTO,
} from '../../../core/api/fintechSaaSAPI.schemas';

type FlatCategory = { id: string; name: string };

/**
 * Feature "Recorrências": lista as regras ativas do tenant e permite criar, editar
 * (apenas descrição+valor, espelhando o PATCH do backend) e cancelar. O painel de
 * criação embute o {@link RruleEditor}. Estado em signals; RxJS só no HTTP.
 */
@Component({
  selector: 'app-recurrence-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCardModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    RruleEditor,
  ],
  templateUrl: './recurrence-list.html',
  styleUrl: './recurrence-list.scss',
})
export class RecurrenceList implements OnInit {
  private service = inject(RecurrenceService);
  private accountService = inject(AccountsService);
  private categoryService = inject(CategoriesService);
  private snackBar = inject(MatSnackBar);

  readonly rules = signal<RecurrenceRuleResponseDTO[]>([]);
  readonly accounts = signal<AccountResponse[]>([]);
  readonly categories = signal<FlatCategory[]>([]);
  readonly loading = signal(false);

  readonly showForm = signal(false);
  readonly editingId = signal<string | null>(null);

  // Modelo do formulário (signals + ngModel).
  readonly description = signal('');
  readonly baseAmount = signal<number | null>(null);
  readonly type = signal<'INCOME' | 'EXPENSE'>('EXPENSE');
  readonly accountId = signal<string | null>(null);
  readonly categoryId = signal<string | null>(null);
  readonly startDate = signal<string>(new Date().toISOString().slice(0, 10));
  readonly rrule = signal('');

  readonly displayedColumns = ['description', 'amount', 'account', 'rule', 'status', 'actions'];
  readonly describeRrule = describeRrule;

  readonly canSubmit = computed(() => {
    if (!this.description().trim() || !this.baseAmount()) return false;
    if (this.editingId()) return true; // edição só mexe em descrição/valor
    return !!this.accountId() && !!this.rrule() && !!this.startDate();
  });

  ngOnInit(): void {
    this.load();
    this.accountService.listAccounts().subscribe((a) => this.accounts.set(a));
    this.categoryService
      .listCategories({ includeArchived: false })
      .subscribe((tree) => this.categories.set(this.flatten(tree)));
  }

  load(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (r) => {
        this.rules.set(r);
        this.loading.set(false);
      },
      error: () => {
        this.snackBar.open('Erro ao carregar recorrências.', 'Fechar', { duration: 5000 });
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.editingId.set(null);
    this.description.set('');
    this.baseAmount.set(null);
    this.type.set('EXPENSE');
    this.accountId.set(null);
    this.categoryId.set(null);
    this.startDate.set(new Date().toISOString().slice(0, 10));
    this.rrule.set('');
    this.showForm.set(true);
  }

  openEdit(rule: RecurrenceRuleResponseDTO): void {
    this.editingId.set(rule.id);
    this.description.set(rule.description);
    this.baseAmount.set(rule.baseAmount);
    this.showForm.set(true);
  }

  closeForm(): void {
    this.showForm.set(false);
  }

  submit(): void {
    if (!this.canSubmit()) return;
    const editing = this.editingId();
    const done = (msg: string) => {
      this.snackBar.open(msg, 'OK', { duration: 3000 });
      this.showForm.set(false);
      this.load();
    };
    if (editing) {
      this.service
        .patch(editing, { description: this.description(), baseAmount: this.baseAmount()! })
        .subscribe({ next: () => done('Recorrência atualizada.'), error: () => this.fail() });
    } else {
      this.service
        .create({
          description: this.description(),
          baseAmount: this.baseAmount()!,
          type: this.type(),
          categoryId: this.categoryId() ?? undefined,
          accountId: this.accountId()!,
          rrule: this.rrule(),
          startDate: this.startDate(),
        })
        .subscribe({ next: () => done('Recorrência criada.'), error: () => this.fail() });
    }
  }

  cancelRule(rule: RecurrenceRuleResponseDTO): void {
    if (!confirm(`Cancelar a recorrência "${rule.description}"?`)) return;
    this.service.cancel(rule.id).subscribe({
      next: () => {
        this.snackBar.open('Recorrência cancelada.', 'OK', { duration: 3000 });
        this.load();
      },
      error: () => this.fail(),
    });
  }

  private fail(): void {
    this.snackBar.open('Erro ao salvar recorrência.', 'Fechar', { duration: 5000 });
  }

  // Achata a árvore de categorias para um select simples, prefixando o caminho.
  private flatten(cats: CategoryResponseDTO[], prefix = '', out: FlatCategory[] = []): FlatCategory[] {
    for (const c of cats) {
      out.push({ id: c.id, name: prefix + c.name });
      if (c.children?.length) this.flatten(c.children, `${prefix}${c.name} → `, out);
    }
    return out;
  }
}
