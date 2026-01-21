import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreditCard, CreditCardCreate } from '../models/credit-card.model';

@Injectable({ providedIn: 'root' })
export class CreditCardService {
  private http = inject(HttpClient);
  
  // Apontando para o seu backend local
  private readonly API_URL = 'http://localhost:8080/credit-cards';

  list(): Observable<CreditCard[]> {
    return this.http.get<CreditCard[]>(this.API_URL);
  }

  getById(id: string): Observable<CreditCard> {
    return this.http.get<CreditCard>(`${this.API_URL}/${id}`);
  }

  create(data: CreditCardCreate): Observable<CreditCard> {
    return this.http.post<CreditCard>(this.API_URL, data);
  }

  update(id: string, data: Partial<CreditCard>): Observable<CreditCard> {
    return this.http.put<CreditCard>(`${this.API_URL}/${id}`, data);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${id}`);
  }
}