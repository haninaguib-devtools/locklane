import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { OpenProjectConsole, ProjectConsoleService } from '../../services/project-console.service';

// The project's consoles page (#179, part of #176): lists the project's open
// consoles from #177's list endpoint, so what is currently running is visible and
// reachable in one place -- this is the page the sidenav "+" and the project
// page's link (#180) will point to. Opening one hands off to the project-console
// page with the chosen session id in the URL; the tab strip there (#178) is what
// reads the `session` query param and activates that console's tab. Reattach
// only: nothing here starts a console.
@Component({
  selector: 'app-consoles-page',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './consoles-page.component.html',
  styleUrl: './consoles-page.component.css',
})
export class ConsolesPageComponent implements OnChanges {
  private readonly service = inject(ProjectConsoleService);
  private readonly router = inject(Router);

  @Input({ required: true }) projectId!: number;

  consoles: OpenProjectConsole[] = [];
  loading = true;
  error = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load(this.projectId);
    }
  }

  private load(projectId: number): void {
    this.consoles = [];
    this.loading = true;
    this.error = false;
    this.service.listOpen(projectId).subscribe({
      next: (consoles) => {
        this.consoles = consoles;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.error = true;
      },
    });
  }

  open(session: OpenProjectConsole): void {
    this.router.navigate(['/projects', this.projectId, 'console'], {
      queryParams: { session: session.sessionId },
    });
  }

  back(): void {
    this.router.navigate(['/projects', this.projectId, 'issues']);
  }
}
