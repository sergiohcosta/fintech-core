import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Member {
  id: string;
  name: string;
  email: string;
  role: 'ADMIN' | 'USER';
}

@Injectable({ providedIn: 'root' })
export class MembersService {
  private http = inject(HttpClient);

  list(): Observable<Member[]> {
    return this.http.get<Member[]>('/api/members');
  }
}
