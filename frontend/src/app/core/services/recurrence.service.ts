import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { RecurrenceRulesService } from '../api/recurrence-rules/recurrence-rules.service';
import {
  ConfirmOccurrenceDTO,
  RecurrenceRuleCreateDTO,
  RecurrenceRulePatchDTO,
  RecurrenceRuleResponseDTO,
  TransactionResponseDTO,
} from '../api/fintechSaaSAPI.schemas';

/**
 * Fachada fina sobre o serviço Orval gerado — concentra os nomes de domínio
 * (list/create/confirm/skip) e isola os componentes do client gerado.
 */
@Injectable({ providedIn: 'root' })
export class RecurrenceService {
  private api = inject(RecurrenceRulesService);

  list(): Observable<RecurrenceRuleResponseDTO[]> {
    return this.api.listRecurrenceRules();
  }

  create(dto: RecurrenceRuleCreateDTO): Observable<RecurrenceRuleResponseDTO> {
    return this.api.createRecurrenceRule(dto);
  }

  get(id: string): Observable<RecurrenceRuleResponseDTO> {
    return this.api.getRecurrenceRule(id);
  }

  patch(id: string, dto: RecurrenceRulePatchDTO): Observable<RecurrenceRuleResponseDTO> {
    return this.api.patchRecurrenceRule(id, dto);
  }

  cancel(id: string): Observable<void> {
    return this.api.cancelRecurrenceRule(id);
  }

  // date no formato ISO yyyy-mm-dd (path param da ocorrência).
  confirm(id: string, date: string, body?: ConfirmOccurrenceDTO): Observable<TransactionResponseDTO> {
    return this.api.confirmOccurrence(id, date, body);
  }

  skip(id: string, date: string): Observable<void> {
    return this.api.skipOccurrence(id, date);
  }
}
