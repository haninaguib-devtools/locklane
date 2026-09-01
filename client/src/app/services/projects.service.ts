import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { Project } from '../models/issue.model';

/** One GitHub account `gh` is logged into on the engine host (#532), and whether it is the active one. */
export interface GithubAccount {
  login: string;
  active: boolean;
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
   * server-side. `githubLogin` (#532) names the host `gh` account the project acts
   * as; omitted, the request carries no such field and the engine behaves as before.
   */
  create(gitUrl: string, name: string, githubLogin?: string): Observable<Project> {
    return this.http.post<Project>('/api/projects', withLogin({ gitUrl, name }, githubLogin));
  }

  /**
   * Creates a brand-new GitHub repository and registers it, async like `create` (#491);
   * `githubLogin` as there. `template` (#536) names a project template on the engine
   * host to commit into the new repository; omitted, the request carries no such field.
   */
  createNew(
    org: string,
    name: string,
    bootstrapTWorkflow: boolean,
    githubLogin?: string,
    template?: string,
  ): Observable<Project> {
    const body = withLogin({ org, name, bootstrapTWorkflow }, githubLogin);
    return this.http.post<Project>('/api/projects/new', template ? { ...body, template } : body);
  }

  /** The project templates on the engine host (#536), sorted by title; empty when there are none. */
  templates(): Observable<ProjectTemplate[]> {
    return this.http
      .get<{ templates: ProjectTemplate[] }>('/api/templates')
      .pipe(map((response) => response.templates ?? []));
  }

  /** The `gh` accounts on the engine host (#532), in gh's own order; empty when there are none or gh is missing. */
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
}

/** The request body with `githubLogin` added only when one was chosen — never as an empty string. */
function withLogin<T extends object>(body: T, githubLogin: string | undefined): T & { githubLogin?: string } {
  return githubLogin ? { ...body, githubLogin } : body;
}
