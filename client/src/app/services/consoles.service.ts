import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ConsolesService {
  private readonly http = inject(HttpClient);
  private readonly closed$ = new Subject<void>();

  /** Every open console session id the caller may see, across all issues (#32). */
  list(): Observable<string[]> {
    return this.http.get<string[]>('/api/consoles');
  }

  /**
   * Fires whenever a console session is closed for good somewhere in the app (#75),
   * so the header indicator can refresh its count without an unrelated reload.
   */
  readonly onClosed = this.closed$.asObservable();

  notifyClosed(): void {
    this.closed$.next();
  }
}
