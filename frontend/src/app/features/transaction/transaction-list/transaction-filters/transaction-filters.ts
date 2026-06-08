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

  accountId          = signal<string | null>(null);
  status             = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type               = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate          = signal<string | null>(null);
  endDate            = signal<string | null>(null);
  groupByPeriod      = signal(false);
  showCustomInterval = signal(false);

  readonly monthChipStates = computed(() => {
    const now = new Date();
    return computeMonthChipStates(
      now.getFullYear(),
      now.getMonth() + 1,
      this.startDate(),
      this.endDate(),
    );
  });

  onAccountChange(val: string | null): void {
    this.accountId.set(val);
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
    this.emit();
  }

  onEndDateChange(val: string): void {
    this.endDate.set(val || null);
    this.emit();
  }

  onGroupByPeriodChange(val: boolean): void {
    this.groupByPeriod.set(val);
    this.emit();
  }

  onMonthChipClick(key: string): void {
    const currentStart = this.startDate();
    const currentEnd   = this.endDate();
    const bounds = monthBounds(key);
    if (currentStart === bounds.startDate && currentEnd === bounds.endDate) {
      this.startDate.set(null);
      this.endDate.set(null);
    } else {
      this.startDate.set(bounds.startDate);
      this.endDate.set(bounds.endDate);
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
  }

  clearFilters(): void {
    this.accountId.set(null);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(null);
    this.endDate.set(null);
    this.showCustomInterval.set(false);
    this.emit();
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
