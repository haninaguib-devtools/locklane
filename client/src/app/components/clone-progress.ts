/**
 * Estimated clone-progress staging (#717): the engine reports only
 * CLONING/READY/FAILED, so while a project is still cloning the client shows
 * staged text derived from how long the wait has run so far. One mapping for
 * the add-project dialog, the sidenav row, and the project console page, so
 * the three surfaces never drift apart.
 */
export function cloneStageHint(elapsedSec: number): string {
  if (elapsedSec < 8) {
    return 'contacting GitHub…';
  }
  if (elapsedSec < 25) {
    return 'cloning repository…';
  }
  return 'preparing workarea…';
}
