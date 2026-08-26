import { deriveProjectName } from './derive-project-name';

describe('deriveProjectName', () => {
  it('takes the last path segment and drops .git', () => {
    expect(deriveProjectName('https://github.com/foo/bar.git')).toBe('bar');
  });

  it('handles an scp-style git URL', () => {
    expect(deriveProjectName('git@github.com:foo/bar.git')).toBe('bar');
  });

  it('handles a trailing slash and no .git suffix', () => {
    expect(deriveProjectName('https://example.com/repo/')).toBe('repo');
  });

  it('returns an empty string for an empty URL', () => {
    expect(deriveProjectName('')).toBe('');
  });
});
