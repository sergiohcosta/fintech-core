import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CreditCardModel, CreditCardCreate } from '../models/credit-card';

@Injectable({ providedIn: 'root' })
export class CreditCardService {
  private http = inject(HttpClient);

  // Use o prefixo /api se configurou proxy, ou o URL completo
  // Assumindo que você configurou o proxy.conf.json ou está rodando local:
  private readonly API_URL = '/api/credit-cards'; // O interceptor vai adicionar o domínio se configurado, ou use 'http://localhost:8080/credit-cards'

  list(): Observable<CreditCardModel[]> {
    return this.http.get<CreditCardModel[]>(this.API_URL);
  }

  getById(id: string): Observable<CreditCardModel> {
    return this.http.get<CreditCardModel>(`${this.API_URL}/${id}`);
  }

  create(data: CreditCardCreate): Observable<CreditCardModel> {
    return this.http.post<CreditCardModel>(this.API_URL, data);
  }

  update(id: string, data: Partial<CreditCardModel>): Observable<CreditCardModel> {
    return this.http.put<CreditCardModel>(`${this.API_URL}/${id}`, data);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${id}`);
  }
}