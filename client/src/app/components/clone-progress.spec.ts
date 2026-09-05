import { cloneStageHint } from './clone-progress';

describe('cloneStageHint', () => {
  it('walks through contact, clone, and prepare as the wait runs on', () => {
    expect(cloneStageHint(0)).toBe('contacting GitHub…');
    expect(cloneStageHint(7)).toBe('contacting GitHub…');
    expect(cloneStageHint(8)).toBe('cloning repository…');
    expect(cloneStageHint(24)).toBe('cloning repository…');
    expect(cloneStageHint(25)).toBe('preparing workarea…');
    expect(cloneStageHint(600)).toBe('preparing workarea…');
  });
});
