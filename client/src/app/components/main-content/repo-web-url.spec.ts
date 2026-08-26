import { repoWebUrl } from './repo-web-url';

describe('repoWebUrl', () => {
  it('derives the web URL from an https remote', () => {
    expect(repoWebUrl('https://github.com/org/repo.git')).toBe('https://github.com/org/repo');
  });

  it('derives the web URL from an https remote with no .git suffix', () => {
    expect(repoWebUrl('https://github.com/org/repo')).toBe('https://github.com/org/repo');
  });

  it('derives the web URL from an ssh remote', () => {
    expect(repoWebUrl('git@github.com:org/repo.git')).toBe('https://github.com/org/repo');
  });

  it('returns null for a non-GitHub remote', () => {
    expect(repoWebUrl('https://gitlab.com/org/repo.git')).toBeNull();
  });
});
