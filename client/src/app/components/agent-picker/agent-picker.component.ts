import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Agent } from '../../services/agent-store';

/**
 * The claude/codex/shell chooser, factored out of {@link ConsoleTabsComponent}'s
 * new-console picker (#140) so the project-level console can reuse the exact same
 * choices without duplicating the "where" (worktree/main) half that only makes
 * sense for an issue console.
 */
@Component({
  selector: 'app-agent-picker',
  standalone: true,
  templateUrl: './agent-picker.component.html',
  styleUrl: './agent-picker.component.css',
})
export class AgentPickerComponent {
  @Input() agent: Agent = 'claude';
  @Output() agentChange = new EventEmitter<Agent>();

  choose(agent: Agent): void {
    this.agent = agent;
    this.agentChange.emit(agent);
  }
}
