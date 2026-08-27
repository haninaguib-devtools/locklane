import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { UsageSnapshot } from '../models/usage.model';

@Injectable({ providedIn: 'root' })
export class UsageService {
  private readonly http = inject(HttpClient);

  snapshot(): Observable<UsageSnapshot> {
    return this.http.get<UsageSnapshot>('/api/usage');
  }
}
