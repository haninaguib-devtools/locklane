// Mirrors dev.locklane.engine.github.GhIssue.
export interface GhIssue {
  number: number;
  title: string;
  state: string;
  labels: string[];
  body: string;
  createdAt: string;
  updatedAt: string;
}

// Mirrors dev.locklane.engine.github.FlowStep.
export interface FlowStep {
  name: string;
  done: boolean;
}

// Mirrors dev.locklane.engine.github.ChecksSummary.
export interface ChecksSummary {
  passing: number;
  failing: number;
  pending: number;
}

// Mirrors dev.locklane.engine.github.IssueDetail.
export interface IssueDetail {
  number: number;
  recordPath: string | null;
  checks: ChecksSummary;
  branch: string | null;
  prNumber: number | null;
  prState: string | null;
  prDraft: boolean;
  flowSteps: FlowStep[];
}

// Mirrors dev.locklane.engine.github.TreeNode.
export interface TreeNode {
  number: number;
  title: string;
  kind: 'INITIATIVE' | 'TASK';
  state: string;
  children: TreeNode[];
}

// Mirrors dev.locklane.engine.persistence.WorktreeController.ResumeSessionView:
// one past Claude/Codex conversation captured in one of the issue's consoles
// (#102), reopenable from the Overview tab (#103). `worktreeId` is the console
// the conversation was captured in, not a console to attach to.
export interface ResumeSession {
  worktreeId: string;
  tool: 'claude' | 'codex';
  resumeId: string;
  capturedAt: string;
}

// Mirrors dev.locklane.engine.persistence.ProjectController.ProjectView.
export interface Project {
  id: number;
  name: string;
  gitUrl: string;
  workareaPath: string;
  defaultBranch: string | null;
  status: 'CLONING' | 'READY' | 'FAILED';
  createdAt: string;
}
