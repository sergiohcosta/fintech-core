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

export interface CreateInvitationRequest {
  email: string;
}

export interface InvitationResponse {
  token: string;
  link: string;
  email: string;
  expiresAt: string;
}

export interface InvitationSummary {
  id: string;
  email: string;
  status: 'PENDING' | 'ACCEPTED' | 'EXPIRED';
  createdAt: string;
  expiresAt: string | null;
  link: string | null;
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

  create(dto: CreateInvitationRequest): Observable<InvitationResponse> {
    return this.http.post<InvitationResponse>('/invites', dto);
  }

  list(): Observable<InvitationSummary[]> {
    return this.http.get<InvitationSummary[]>('/invites');
  }

  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`/invites/${id}`);
  }
}
