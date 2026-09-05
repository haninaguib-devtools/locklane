import { cloneStageHint, elapsedSeconds } from './clone-progress';

describe('cloneStageHint', () => {
  it('starts at contacting GitHub', () => {
    expect(cloneStageHint(0)).toBe('contacting GitHub…');
    expect(cloneStageHint(2)).toBe('contacting GitHub…');
  });

  it('moves to cloning repository once a few seconds have passed', () => {
    expect(cloneStageHint(3)).toBe('cloning repository…');
    expect(cloneStageHint(9)).toBe('cloning repository…');
  });

  it('settles on preparing workarea for a longer wait', () => {
    expect(cloneStageHint(10)).toBe('preparing workarea…');
    expect(cloneStageHint(120)).toBe('preparing workarea…');
  });
});

describe('elapsedSeconds', () => {
  it('floors to whole seconds', () => {
    expect(elapsedSeconds(0, 2999)).toBe(2);
    expect(elapsedSeconds(0, 3000)).toBe(3);
  });

  it('never goes negative when the clock looks like it went backwards', () => {
    expect(elapsedSeconds(1000, 0)).toBe(0);
  });
});
