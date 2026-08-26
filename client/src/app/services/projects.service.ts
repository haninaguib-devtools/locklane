import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Project } from '../models/issue.model';

@Injectable({ providedIn: 'root' })
export class ProjectsService {
  private readonly http = inject(HttpClient);

  list(): Observable<Project[]> {
    return this.http.get<Project[]>('/api/projects');
  }

  /** Creates a project and kicks off its async clone (#42); a blank name is derived server-side. */
  create(gitUrl: string, name: string): Observable<Project> {
    return this.http.post<Project>('/api/projects', { gitUrl, name });
  }

  /** Re-clones a failed project from scratch (#42). */
  retry(id: number): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/retry`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/projects/${id}`);
  }
}
