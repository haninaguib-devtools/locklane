import { Component } from '@angular/core';
import { SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [SidenavComponent, MainContentComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  selectedIssue: number | null = null;

  select(issueNumber: number): void {
    this.selectedIssue = issueNumber;
  }
}
