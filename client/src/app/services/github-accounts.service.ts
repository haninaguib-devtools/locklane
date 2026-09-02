import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GithubAccount } from './projects.service';

/** GitHub's device-flow code to show the operator, and the flow id to poll status with (#550). */
export interface DeviceFlowStarted {
  flowId: string;
  userCode: string;
  verificationUri: string;
  expiresInSeconds: number;
}

/** One poll of a device flow's progress (#550). */
export interface DeviceFlowStatus {
  status: 'PENDING' | 'COMPLETE' | 'FAILED';
  account: GithubAccount | null;
  errorMessage: string | null;
}

/** The GitHub accounts page's backend (#550): sign in (device flow or paste a token), list, remove. */
@Injectable({ providedIn: 'root' })
export class GithubAccountsService {
  private readonly http = inject(HttpClient);

  /** The caller's own accounts, newest first. */
  list(): Observable<GithubAccount[]> {
    return this.http
      .get<{ accounts: GithubAccount[] }>('/api/github/accounts')
      .pipe(map((response) => response.accounts ?? []));
  }

  /** Validates and stores a pasted token (#550) — the engine rejects one without the `repo` scope. */
  addByToken(token: string): Observable<GithubAccount> {
    return this.http.post<GithubAccount>('/api/github/accounts/token', { token });
  }

  /** Starts GitHub's device flow; poll {@link deviceFlowStatus} with the returned `flowId` until it settles. */
  startDeviceFlow(): Observable<DeviceFlowStarted> {
    return this.http.post<DeviceFlowStarted>('/api/github/accounts/device/start', {});
  }

  deviceFlowStatus(flowId: string): Observable<DeviceFlowStatus> {
    return this.http.get<DeviceFlowStatus>(`/api/github/accounts/device/${flowId}`);
  }

  /** Removes an account; the engine refuses (409) one still chosen by a project. */
  remove(id: number): Observable<void> {
    return this.http.delete<void>(`/api/github/accounts/${id}`);
  }
}
