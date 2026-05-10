import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CategoryModel, CategoryCreate } from '../models/category';

@Injectable({
  providedIn: 'root'
})
export class CategoryService {
  private http = inject(HttpClient);
  private readonly API_URL = '/api/categories';

  list(): Observable<CategoryModel[]> {
    return this.http.get<CategoryModel[]>(this.API_URL);
  }

  getById(id: string): Observable<CategoryModel> {
    return this.http.get<CategoryModel>(this.API_URL + '/' + id);
  }

  create(data: CategoryCreate): Observable<CategoryModel> {
    return this.http.post<CategoryModel>(this.API_URL, data);
  }

  update(id: string, data: Partial<CategoryCreate>): Observable<CategoryModel> {
    return this.http.put<CategoryModel>(this.API_URL + '/' + id, data);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(this.API_URL + '/' + id);
  }
}
