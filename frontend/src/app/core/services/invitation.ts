import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface InvitationInfo {
  email: string;
  tenantName: string;
}

export interface AcceptInviteRequest {
  token: string;
  name: string;
  password: string;
}

@Injectable({ providedIn: 'root' })
export class InvitationService {
  private http = inject(HttpClient);

  validateToken(token: string): Observable<InvitationInfo> {
    return this.http.get<InvitationInfo>(`/invites/${token}`);
  }

  acceptInvite(dto: AcceptInviteRequest): Observable<{ token: string }> {
    return this.http.post<{ token: string }>('/auth/accept-invite', dto);
  }
}
