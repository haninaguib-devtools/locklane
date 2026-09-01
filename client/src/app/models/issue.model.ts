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

// Mirrors dev.locklane.engine.github.CheckRun: one CI check, its outcome, and the
// link to its run (#397). `url` is null when the rollup carried no link.
export interface CheckRun {
  name: string;
  state: 'passing' | 'failing' | 'pending';
  url: string | null;
}

// Mirrors dev.locklane.engine.github.ChecksSummary.
export interface ChecksSummary {
  passing: number;
  failing: number;
  pending: number;
  runs: CheckRun[];
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
  // Same PR-derived signal as IssueDetail.branch's presence (#110).
  hasActiveBranch: boolean;
  // Verbatim from GhIssue.labels (#111) -- the sidebar's tag filter picks its own
  // classification subset out of these.
  labels: string[];
  children: TreeNode[];
}

// Mirrors dev.locklane.engine.persistence.WorktreeController.ResumeSessionView:
// one past Claude/Codex/OpenCode conversation captured in one of the issue's
// consoles (#102), reopenable from the Overview tab (#103). Since #372 the
// project console page lists its own consoles' conversations through the same
// shape. `worktreeId` is the console the conversation was captured in, not a
// console to attach to.
export interface ResumeSession {
  worktreeId: string;
  tool: 'claude' | 'codex' | 'opencode';
  resumeId: string;
  capturedAt: string;
  // The short name the CLI generated for the conversation (#373), or null when it has
  // none: too short a conversation to have been titled, a Codex older than v0.150.0,
  // or a tool that isn't installed. Null is ordinary, and falls back to the captured
  // time in the list.
  title: string | null;
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
  accentColor: string | null;
  /** The project template this project was created from (#536), or null for none. */
  template: string | null;
  /**
   * When the template's one seeded console was launched (#537), or null while the
   * project still owes it (and always null with no template). Optional so the many
   * spec fixtures that build a Project literal need not all name it.
   */
  templateSeededAt?: string | null;
}
