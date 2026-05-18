import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TransactionRequest, TransactionResponse } from '../models/transaction';

@Injectable({
  providedIn: 'root'
})
export class TransactionService {
  private http = inject(HttpClient);
  private readonly API_URL = '/api/transactions';

  list(): Observable<TransactionResponse[]> {
    return this.http.get<TransactionResponse[]>(this.API_URL);
  }

  getById(id: string): Observable<TransactionResponse> {
    return this.http.get<TransactionResponse>(`${this.API_URL}/${id}`);
  }

  create(data: TransactionRequest): Observable<TransactionResponse[]> {
    return this.http.post<TransactionResponse[]>(this.API_URL, data);
  }

  update(id: string, data: Partial<TransactionRequest>): Observable<TransactionResponse> {
    return this.http.put<TransactionResponse>(`${this.API_URL}/${id}`, data);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${id}`);
  }
}
