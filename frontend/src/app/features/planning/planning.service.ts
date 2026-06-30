import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BudgetService } from '../../core/api/budget/budget.service';
import { TenantService } from '../../core/api/tenant/tenant.service';
import { RecurrenceRulesService } from '../../core/api/recurrence-rules/recurrence-rules.service';
import {
  BudgetCycleOpenRequest,
  BudgetCyclePageResponse,
  BudgetCycleResponse,
  BudgetItemCreateRequest,
  BudgetItemLinkRequest,
  BudgetItemResponse,
  BudgetItemUpdateRequest,
  RecurrenceRuleCreateDTO,
  RecurrenceRulePatchDTO,
  RecurrenceRuleResponseDTO,
  TenantSettingsPatchRequest,
} from '../../core/api/fintechSaaSAPI.schemas';

@Injectable({ providedIn: 'root' })
export class PlanningService {
  private readonly budget = inject(BudgetService);
  private readonly tenant = inject(TenantService);
  private readonly recurrenceRules = inject(RecurrenceRulesService);

  getCurrentCycle(): Observable<BudgetCycleResponse> {
    return this.budget.getCurrentBudgetCycle();
  }

  openCycle(req: BudgetCycleOpenRequest): Observable<BudgetCycleResponse> {
    return this.budget.openBudgetCycle(req);
  }

  closeCycle(id: string, force = false): Observable<BudgetCycleResponse> {
    return this.budget.closeBudgetCycle(id, force ? { force: true } : undefined);
  }

  deleteCycle(id: string): Observable<void> {
    return this.budget.deleteBudgetCycle(id);
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

  listRecurring(): Observable<RecurrenceRuleResponseDTO[]> {
    return this.recurrenceRules.listRecurrenceRules();
  }

  createRecurring(req: RecurrenceRuleCreateDTO): Observable<RecurrenceRuleResponseDTO> {
    return this.recurrenceRules.createRecurrenceRule(req);
  }

  updateRecurring(id: string, req: RecurrenceRulePatchDTO): Observable<RecurrenceRuleResponseDTO> {
    return this.recurrenceRules.patchRecurrenceRule(id, req);
  }

  deleteRecurring(id: string): Observable<void> {
    return this.recurrenceRules.cancelRecurrenceRule(id);
  }

  reactivateRecurring(id: string): Observable<RecurrenceRuleResponseDTO> {
    return this.recurrenceRules.reactivateRecurrenceRule(id);
  }

  getSettings(): Observable<TenantSettingsPatchRequest> {
    return this.tenant.getTenantSettings();
  }

  patchTenantSettings(req: TenantSettingsPatchRequest): Observable<void> {
    return this.tenant.patchTenantSettings(req);
  }
}
