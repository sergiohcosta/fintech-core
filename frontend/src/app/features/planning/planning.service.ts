import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BudgetService } from '../../core/api/budget/budget.service';
import { TenantService } from '../../core/api/tenant/tenant.service';
import {
  BudgetCycleOpenRequest,
  BudgetCyclePageResponse,
  BudgetCyclePreview,
  BudgetCycleResponse,
  BudgetItemCreateRequest,
  BudgetItemLinkRequest,
  BudgetItemRealizeRequest,
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

  closeCycle(id: string, force = false): Observable<BudgetCycleResponse> {
    return this.budget.closeBudgetCycle(id, force ? { force } : undefined);
  }

  listCycles(page = 0, size = 12): Observable<BudgetCyclePageResponse> {
    return this.budget.listBudgetCycles({ page, size });
  }

  deleteCycle(id: string): Observable<void> {
    return this.budget.deleteBudgetCycle(id);
  }

  getCycle(id: string): Observable<BudgetCycleResponse> {
    return this.budget.getBudgetCycle(id);
  }

  syncInstallments(id: string): Observable<BudgetCycleResponse> {
    return this.budget.syncInstallments(id);
  }

  previewCycle(startDay?: number): Observable<BudgetCyclePreview> {
    return this.budget.previewBudgetCycle(startDay !== undefined ? { startDay } : undefined);
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

  realizeItem(id: string, req: BudgetItemRealizeRequest): Observable<BudgetItemResponse> {
    return this.budget.realizeBudgetItem(id, req);
  }

  unrealizeItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.unrealizeBudgetItem(id);
  }

  skipItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.skipBudgetItem(id);
  }

  unskipItem(id: string): Observable<BudgetItemResponse> {
    return this.budget.unskipBudgetItem(id);
  }

  reactivateRecurring(id: string): Observable<RecurringBudgetItemResponse> {
    return this.budget.reactivateRecurringBudgetItem(id);
  }

  listRecurring(active?: boolean): Observable<RecurringBudgetItemResponse[]> {
    return this.budget.listRecurringBudgetItems(active !== undefined ? { active } : undefined);
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
