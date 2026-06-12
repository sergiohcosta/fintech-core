import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BudgetService } from '../../core/api/budget/budget.service';
import { TenantService } from '../../core/api/tenant/tenant.service';
import {
  BudgetCycleOpenRequest,
  BudgetCyclePageResponse,
  BudgetCycleResponse,
  BudgetItemCreateRequest,
  BudgetItemLinkRequest,
  BudgetItemResponse,
  BudgetItemUpdateRequest,
  RecurringBudgetItemRequest,
  RecurringBudgetItemResponse,
  TenantSettingsPatchRequest,
} from '../../core/api/fintechSaaSAPI.schemas';

@Injectable({ providedIn: 'root' })
export class PlanningService {
  private readonly budget = inject(BudgetService);
  private readonly tenant = inject(TenantService);

  getCurrentCycle(): Observable<BudgetCycleResponse> {
    return this.budget.getCurrentBudgetCycle();
  }

  openCycle(req: BudgetCycleOpenRequest): Observable<BudgetCycleResponse> {
    return this.budget.openBudgetCycle(req);
  }

  closeCycle(id: string): Observable<BudgetCycleResponse> {
    return this.budget.closeBudgetCycle(id);
  }

  listCycles(page = 0, size = 12): Observable<BudgetCyclePageResponse> {
    return this.budget.listBudgetCycles({ page, size });
  }

  getCycle(id: string): Observable<BudgetCycleResponse> {
    return this.budget.getBudgetCycle(id);
  }

  syncInstallments(id: string): Observable<BudgetCycleResponse> {
    return this.budget.syncInstallments(id);
  }

  createItem(cycleId: string, req: BudgetItemCreateRequest): Observable<BudgetItemResponse> {
    return this.budget.createBudgetItem(cycleId, req);
  }

  updateItem(id: string, req: BudgetItemUpdateRequest): Observable<BudgetItemResponse> {
    return this.budget.updateBudgetItem(id, req);
  }

  deleteItem(id: string): Observable<void> {
    return this.budget.deleteBudgetItem(id);
  }

  linkItem(id: string, req: BudgetItemLinkRequest): Observable<BudgetItemResponse> {
    return this.budget.linkBudgetItem(id, req);
  }

  unlinkItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.unlinkBudgetItem(id);
  }

  listRecurring(): Observable<RecurringBudgetItemResponse[]> {
    return this.budget.listRecurringBudgetItems();
  }

  createRecurring(req: RecurringBudgetItemRequest): Observable<RecurringBudgetItemResponse> {
    return this.budget.createRecurringBudgetItem(req);
  }

  updateRecurring(id: string, req: RecurringBudgetItemRequest): Observable<RecurringBudgetItemResponse> {
    return this.budget.updateRecurringBudgetItem(id, req);
  }

  deleteRecurring(id: string): Observable<void> {
    return this.budget.deleteRecurringBudgetItem(id);
  }

  patchTenantSettings(req: TenantSettingsPatchRequest): Observable<void> {
    return this.tenant.patchTenantSettings(req);
  }
}
