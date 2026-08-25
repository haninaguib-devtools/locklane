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
