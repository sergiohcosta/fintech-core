import { Component, computed, input, OnInit, output, signal } from '@angular/core';
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
import { TransactionFilters, DEFAULT_FILTERS, currentMonthFilters, currentMonthKey } from './transaction-filters.types';
import { monthBounds, computeMonthChipStates, resolveMonthKey } from '../transaction-list.utils';

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
export class TransactionFiltersComponent implements OnInit {
  accounts       = input<AccountResponse[]>([]);
  initialFilters = input<TransactionFilters>(DEFAULT_FILTERS);
  filterChange   = output<TransactionFilters>();

  accountIds         = signal<string[]>([]);
  status             = signal<'PENDING' | 'PAID' | 'CANCELLED' | null>(null);
  type               = signal<'INCOME' | 'EXPENSE' | null>(null);
  startDate          = signal<string | null>(null);
  endDate            = signal<string | null>(null);
  groupByPeriod      = signal(false);
  groupByInvoice     = signal(false);
  description        = signal<string | null>(null);
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

  ngOnInit(): void {
    const f = this.initialFilters();
    this.accountIds.set(f.accountIds);
    this.status.set(f.status);
    this.type.set(f.type);
    this.startDate.set(f.startDate);
    this.endDate.set(f.endDate);
    this.groupByPeriod.set(f.groupByPeriod);
    this.groupByInvoice.set(f.groupByInvoice);
    this.description.set(null); // descrição nunca é restaurada (busca pontual)
    if (f.startDate && f.endDate) {
      const key = resolveMonthKey(f.startDate, f.endDate);
      if (key && key !== 'custom') {
        this.selectedMonths.set([key]);
      } else {
        this.showCustomInterval.set(true);
      }
    }
  }

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
    if (val) this.groupByInvoice.set(false); // mutuamente exclusivos
    this.emit();
  }

  onGroupByInvoiceChange(val: boolean): void {
    this.groupByInvoice.set(val);
    if (val) this.groupByPeriod.set(false); // mutuamente exclusivos
    this.emit();
  }

  onDescriptionChange(val: string): void {
    this.description.set(val.trim() || null);
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
    const defaults = currentMonthFilters();
    this.accountIds.set([]);
    this.status.set(null);
    this.type.set(null);
    this.startDate.set(defaults.startDate);
    this.endDate.set(defaults.endDate);
    this.groupByPeriod.set(false);
    this.groupByInvoice.set(false);
    this.description.set(null);
    this.showCustomInterval.set(false);
    this.selectedMonths.set([currentMonthKey()]);
    this.emit();
  }

  private emit(): void {
    this.filterChange.emit({
      accountIds:     this.accountIds(),
      status:         this.status(),
      type:           this.type(),
      startDate:      this.startDate(),
      endDate:        this.endDate(),
      groupByPeriod:  this.groupByPeriod(),
      groupByInvoice: this.groupByInvoice(),
      description:    this.description(),
    });
  }
}
