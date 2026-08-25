import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ConsolesService {
  private readonly http = inject(HttpClient);

  /** Every open console session id the caller may see, across all issues (#32). */
  list(): Observable<string[]> {
    return this.http.get<string[]>('/api/consoles');
  }
}
