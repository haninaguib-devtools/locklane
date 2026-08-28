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
  // The project-level console (#140), consolidated from two pages into this one
  // (#221) -- reached from the sidenav's "+" or the project summary's console
  // button, which create a session when none is open yet or jump back into an
  // existing one.
  { path: 'projects/:projectId/console', children: [] },
  // The workspace Overview (#197): no project id picked, so AppComponent renders
  // it directly -- no redirect into a project the way #43 used to. Not logged in
  // / no projects yet: OverviewComponent and AppComponent's own login check
  // reproduce those same states.
  { path: '', children: [] },
];
