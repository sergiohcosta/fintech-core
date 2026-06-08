import { Component, computed, input, output, signal } from '@angular/core';
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
import { monthBounds, computeMonthChipStates } from '../transaction-list.utils';

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

  accountIds         = signal<string[]>([]);
  status             = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type               = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate          = signal<string | null>(null);
  endDate            = signal<string | null>(null);
  groupByPeriod      = signal(false);
  showCustomInterval = signal(false);
  selectedMonths     = signal<string[]>([]);

  readonly monthChipStates = computed(() => {
    const now = new Date();
    const selected = new Set(this.selectedMonths());
    return computeMonthChipStates(
      now.getFullYear(),
      now.getMonth() + 1,
      null,
      null,
    ).map(chip => ({ ...chip, active: selected.has(chip.key) }));
  });

  onAccountChange(val: string[]): void {
    this.accountIds.set(val ?? []);
    this.emit();
  }

  onStatusChange(val: 'PENDING' | 'PAID' | 'CANCELLED' | null): void {
    this.status.set(val !== null && this.status() === val ? null : val);
    this.emit();
  }

  onTypeChange(val: 'INCOME' | 'EXPENSE' | null): void {
    this.type.set(val !== null && this.type() === val ? null : val);
    this.emit();
  }

  onStartDateChange(val: string): void {
    this.startDate.set(val || null);
    this.selectedMonths.set([]);
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.selectedMonths.set([]);
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    this.emit();
  }

  onMonthChipClick(key: string, ctrl: boolean): void {
    const current = this.selectedMonths();

    let next: string[];
    if (ctrl) {
      next = current.includes(key)
        ? current.filter(k => k !== key)
        : [...current, key];
    } else {
      next = current.length === 1 && current[0] === key ? [] : [key];
    }

    this.selectedMonths.set(next);
    this.applyMonthSelection(next);
  }

  private applyMonthSelection(keys: string[]): void {
    if (keys.length === 0) {
      this.startDate.set(null);
      this.endDate.set(null);
    } else {
      const sorted = [...keys].sort();
      this.startDate.set(monthBounds(sorted[0]).startDate);
      this.endDate.set(monthBounds(sorted[sorted.length - 1]).endDate);
      this.showCustomInterval.set(false);
    }
    this.emit();
  }

  onCustomIntervalToggle(): void {
    const next = !this.showCustomInterval();
    this.showCustomInterval.set(next);
    if (!next) {
      this.startDate.set(null);
      this.endDate.set(null);
      this.emit();
    }
    this.selectedMonths.set([]);
  }

  clearFilters(): void {
    this.accountIds.set([]);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.showCustomInterval.set(false);
    this.selectedMonths.set([]);
    this.emit();
  }

  private emit(): void {
    this.filterChange.emit({
      accountIds:    this.accountIds(),
      status:        this.status(),
      type:          this.type(),
      startDate:     this.startDate(),
      endDate:       this.endDate(),
      groupByPeriod: this.groupByPeriod(),
    });
  }
}
