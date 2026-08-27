import { Routes } from '@angular/router';

// Component-less: AppComponent is the whole app shell and reads the current
// project/issue id directly off this route (see app.component.ts) rather than
// delegating rendering to a router-outlet.
export const routes: Routes = [
  { path: 'projects/:projectId/issues/:id', children: [] },
  // A project with no issue selected yet -- clicking a project's name in the
  // sidenav lands here. Since #85 AppComponent renders the project's own
  // summary here rather than an empty state.
  { path: 'projects/:projectId/issues', children: [] },
  // The project-level console (#140) that starts a new issue's discussion before
  // any issue exists -- reached from the "New issue (agent)" button on the
  // project's own summary page above, never from the sidenav.
  { path: 'projects/:projectId/console', children: [] },
  // The project's open consoles (#179): every console currently running for this
  // project, each reattachable -- the page the sidenav "+" and the project page
  // will link to (#180).
  { path: 'projects/:projectId/consoles', children: [] },
  // The workspace Overview (#197): no project id picked, so AppComponent renders
  // it directly -- no redirect into a project the way #43 used to. Not logged in
  // / no projects yet: OverviewComponent and AppComponent's own login check
  // reproduce those same states.
  { path: '', children: [] },
];
