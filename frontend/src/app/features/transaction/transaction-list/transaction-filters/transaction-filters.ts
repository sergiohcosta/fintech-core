import { Component, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AccountResponse } from '../../../../core/api/fintechSaaSAPI.schemas';
import { TransactionFilters, DEFAULT_FILTERS } from './transaction-filters.types';
import { resolveMonthKey, monthBounds, formatMonthLabel } from '../transaction-list.utils';

@Component({
  selector: 'app-transaction-filters',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatFormFieldModule, MatSelectModule, MatInputModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatTooltipModule,
  ],
  templateUrl: './transaction-filters.html',
  styleUrl: './transaction-filters.scss',
})
export class TransactionFiltersComponent {
  accounts     = input<AccountResponse[]>([]);
  filterChange = output<TransactionFilters>();

  accountId     = signal<string | null>(null);
  status        = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type          = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate     = signal<string | null>(null);
  endDate       = signal<string | null>(null);
  groupByPeriod = signal(false);
  monthLabel    = signal('');

  onAccountChange(val: string | null): void {
    this.accountId.set(val);
    this.emit();
  }

  onStatusChange(val: 'PENDING' | 'PAID' | 'CANCELLED' | null): void {
    this.status.set(val);
    this.emit();
  }

  onTypeChange(val: 'INCOME' | 'EXPENSE' | null): void {
    this.type.set(val);
    this.emit();
  }

  onStartDateChange(val: string): void {
    this.startDate.set(val || null);
    this.syncMonthLabel();
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.syncMonthLabel();
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    this.emit();
  }

  goToPreviousMonth(): void {
    const key = this.resolveCurrentKey();
    const [year, month] = key.split('-').map(Number);
    const prev = new Date(year, month - 2, 1);
    this.applyMonth(`${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`);
  }

  goToNextMonth(): void {
    const key = this.resolveCurrentKey();
    const [year, month] = key.split('-').map(Number);
    const next = new Date(year, month, 1);
    this.applyMonth(`${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}`);
  }

  clearFilters(): void {
    this.accountId.set(null);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.monthLabel.set('');
    this.emit();
  }

  private applyMonth(key: string): void {
    const bounds = monthBounds(key);
    this.startDate.set(bounds.startDate);
    this.endDate.set(bounds.endDate);
    this.monthLabel.set(formatMonthLabel(key));
    this.emit();
  }

  private syncMonthLabel(): void {
    const key = resolveMonthKey(this.startDate(), this.endDate());
    if (!key) {
      this.monthLabel.set('');
    } else if (key === 'custom') {
      this.monthLabel.set('Personalizado');
    } else {
      this.monthLabel.set(formatMonthLabel(key));
    }
  }

  private resolveCurrentKey(): string {
    const key = resolveMonthKey(this.startDate(), this.endDate());
    if (key && key !== 'custom') return key;
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private emit(): void {
    this.filterChange.emit({
      accountId:     this.accountId(),
      status:        this.status(),
      type:          this.type(),
      startDate:     this.startDate(),
      endDate:       this.endDate(),
      groupByPeriod: this.groupByPeriod(),
    });
  }
}
