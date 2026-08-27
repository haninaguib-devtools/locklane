import { inject } from '@angular/core';
import { Router, Routes, UrlTree } from '@angular/router';
import { Observable, catchError, map, of } from 'rxjs';
import { ProjectsService } from './services/projects.service';

// Component-less: AppComponent is the whole app shell and reads the current
// project/issue id directly off this route (see app.component.ts) rather than
// delegating rendering to a router-outlet.
export const routes: Routes = [
  { path: 'projects/:projectId/issues/:id', children: [] },
  // A project with no issue selected yet -- the default-project redirect below
  // lands here, and so does clicking a project's name in the sidenav. Since #85
  // AppComponent renders the project's own summary here rather than an empty state.
  { path: 'projects/:projectId/issues', children: [] },
  // The project-level console (#140) that starts a new issue's discussion before
  // any issue exists -- reached from the "New issue (agent)" button on the
  // project's own summary page above, never from the sidenav.
  { path: 'projects/:projectId/console', children: [] },
  // The project's open consoles (#179): every console currently running for this
  // project, each reattachable -- the page the sidenav "+" and the project page
  // will link to (#180).
  { path: 'projects/:projectId/consoles', children: [] },
  // No project-picker UI exists yet (#44/#45) -- landing at '/' with no project id
  // picked resolves to the first project the caller can see (#43), so the app keeps
  // working exactly as it did before projects existed. Not logged in / no projects
  // yet: the guard lets '' through unchanged and AppComponent's own login/empty
  // states handle the rest.
  { path: '', canActivate: [defaultProjectRedirect], children: [] },
];

export function defaultProjectRedirect(): Observable<boolean | UrlTree> {
  const projectsService = inject(ProjectsService);
  const router = inject(Router);
  return projectsService.list().pipe(
    map((projects) => (projects.length > 0 ? router.parseUrl(`/projects/${projects[0].id}/issues`) : true)),
    // Not logged in yet (401) or the request otherwise failed: nothing to redirect
    // to. AppComponent's own isLoggedIn() check decides what renders, independent
    // of routing -- let '' activate as-is rather than blocking navigation.
    catchError(() => of(true)),
  );
}
