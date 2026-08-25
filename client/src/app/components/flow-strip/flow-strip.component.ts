import { Component, Input } from '@angular/core';
import { FlowStep } from '../../models/issue.model';

@Component({
  selector: 'app-flow-strip',
  standalone: true,
  templateUrl: './flow-strip.component.html',
  styleUrl: './flow-strip.component.css',
})
export class FlowStripComponent {
  @Input() steps: FlowStep[] = [];

  // The current stage: the first not-done step, or the last step once everything is
  // done. Matches how the mockup shows a checkmark on completed stages and a filled
  // dot on exactly one, current, stage.
  currentIndex(): number {
    const firstNotDone = this.steps.findIndex((s) => !s.done);
    return firstNotDone === -1 ? this.steps.length - 1 : firstNotDone;
  }
}
