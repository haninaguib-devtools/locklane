import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { Project } from '../models/issue.model';

/** One GitHub account the caller has signed in to Locklane (#550) — never carries its token. */
export interface GithubAccount {
  id: number;
  login: string;
  scopes: string[];
  hasWorkflowScope: boolean;
  createdAt: string;
}

/** One project template on the engine host (#536): its directory name, and the frontmatter's title and description. */
export interface ProjectTemplate {
  name: string;
  title: string;
  description: string;
}

@Injectable({ providedIn: 'root' })
export class ProjectsService {
  private readonly http = inject(HttpClient);

  list(): Observable<Project[]> {
    return this.http.get<Project[]>('/api/projects');
  }

  /**
   * Creates a project and kicks off its async clone (#42); a blank name is derived
   * server-side. `githubAccountId` (#550) names one of the caller's own GitHub
   * accounts for the project to act as; omitted, the request carries no such field
   * and the project has no GitHub credentials of its own.
   */
  create(gitUrl: string, name: string, githubAccountId?: number): Observable<Project> {
    return this.http.post<Project>('/api/projects', withAccount({ gitUrl, name }, githubAccountId));
  }

  /**
   * Creates a brand-new GitHub repository and registers it, async like `create` (#491);
   * `githubAccountId` as there. `template` (#536) names a project template on the
   * engine host to commit into the new repository; omitted, the request carries no
   * such field.
   */
  createNew(
    org: string,
    name: string,
    bootstrapTWorkflow: boolean,
    githubAccountId?: number,
    template?: string,
  ): Observable<Project> {
    const body = withAccount({ org, name, bootstrapTWorkflow }, githubAccountId);
    return this.http.post<Project>('/api/projects/new', template ? { ...body, template } : body);
  }

  /** The project templates on the engine host (#536), sorted by title; empty when there are none. */
  templates(): Observable<ProjectTemplate[]> {
    return this.http
      .get<{ templates: ProjectTemplate[] }>('/api/templates')
      .pipe(map((response) => response.templates ?? []));
  }

  /** The caller's own GitHub accounts (#550), newest first; empty when they have added none. */
  githubAccounts(): Observable<GithubAccount[]> {
    return this.http
      .get<{ accounts: GithubAccount[] }>('/api/github/accounts')
      .pipe(map((response) => response.accounts ?? []));
  }

  /** Re-clones a failed project from scratch (#42). */
  retry(id: number): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/retry`, {});
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/projects/${id}`);
  }

  /** Sets this project's accent color (#427/#428) — a 6-digit hex string like `#c15f3c`. */
  setAccentColor(id: number, accentColor: string): Observable<void> {
    return this.http.put<void>(`/api/projects/${id}/accent-color`, { accentColor });
  }

  /**
   * Persists the caller's new sidenav order (#541) — `orderedIds` must be exactly the
   * caller's own current project ids, in the order they now belong in.
   */
  setOrder(orderedIds: number[]): Observable<void> {
    return this.http.put<void>('/api/projects/order', { orderedIds });
  }
}

/** The request body with `githubAccountId` added only when one was chosen. */
function withAccount<T extends object>(
  body: T,
  githubAccountId: number | undefined,
): T & { githubAccountId?: number } {
  return githubAccountId ? { ...body, githubAccountId } : body;
}
