import { OpenShell } from '../../services/shells.service';

/** One sidenav row: the shell plus its auto-generated label. */
export interface ShellRow {
  shell: OpenShell;
  label: string;
}

/**
 * Row labels for one project's shells, following the tab-label convention
 * (console-labels.ts): the location, plus an index from the second shell of that
 * location on — `Main`, `Main 2`, `#438`, `#438 · wtree 2` — and never an agent
 * suffix, since every row here is already known to be a shell. Order is preserved;
 * the caller passes one project's shells already sorted.
 */
export function labelShells(shells: OpenShell[]): ShellRow[] {
  const seen = new Map<string, number>();
  return shells.map((shell) => {
    const location = shell.mainCheckout ? 'Main' : `#${shell.issueNumber}`;
    const count = (seen.get(location) ?? 0) + 1;
    seen.set(location, count);
    const label = shell.mainCheckout
      ? count > 1
        ? `${location} ${count}`
        : location
      : count > 1
        ? `${location} · wtree ${count}`
        : location;
    return { shell, label };
  });
}

/** What the row actually shows: the user's own name when there is one (#393). */
export function shellRowText(row: ShellRow): string {
  const name = row.shell.displayName?.trim();
  return name ? name : row.label;
}
