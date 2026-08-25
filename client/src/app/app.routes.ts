import { Routes } from '@angular/router';

// Component-less: AppComponent is the whole app shell and reads the current
// issue id directly off this route (see app.component.ts) rather than
// delegating rendering to a router-outlet.
export const routes: Routes = [{ path: 'issues/:id', children: [] }];
