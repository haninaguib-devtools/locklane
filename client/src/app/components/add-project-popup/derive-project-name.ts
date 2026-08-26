/**
 * Client-side preview of the name the backend derives from a git URL when the
 * name field is left blank (mirrors ProjectCheckoutService.deriveName, #42) --
 * the actual derivation still happens server-side on submit; this only drives
 * the popup's live prefill.
 */
export function deriveProjectName(gitUrl: string): string {
  const trimmed = gitUrl.replace(/\/+$/, '');
  const lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
  const tail = lastSlash >= 0 ? trimmed.slice(lastSlash + 1) : trimmed;
  return tail.endsWith('.git') ? tail.slice(0, -4) : tail;
}
