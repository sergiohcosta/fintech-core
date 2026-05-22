import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DashboardSummary } from '../models/dashboard';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private http = inject(HttpClient);
  private readonly API_URL = '/api/dashboard';

  getSummary(month: string): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.API_URL}/summary`, {
      params: { month }
    });
  }
}
