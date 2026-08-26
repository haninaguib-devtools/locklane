/**
 * Turns a project's git remote URL into its GitHub web URL, for building issue and
 * PR links on the overview tab (#96). Returns null for anything not recognizably
 * GitHub, so callers can fall back to plain text.
 */
export function repoWebUrl(gitUrl: string): string | null {
  const https = gitUrl.match(/^https?:\/\/github\.com\/([^/]+\/[^/]+?)(?:\.git)?\/?$/);
  if (https) {
    return `https://github.com/${https[1]}`;
  }
  const ssh = gitUrl.match(/^git@github\.com:([^/]+\/[^/]+?)(?:\.git)?\/?$/);
  if (ssh) {
    return `https://github.com/${ssh[1]}`;
  }
  return null;
}
