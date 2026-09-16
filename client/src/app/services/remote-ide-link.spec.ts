import { CODE_SERVER_IDE } from './default-ide-store';
import {
  GATEWAY_REMOTE_ID,
  REMOTE_IDES,
  RemoteIdeLink,
  VSCODE_REMOTE_ID,
  gatewayLinkIsUsable,
  gatewayUnconfiguredHint,
  isRemoteIde,
  jetbrainsGatewayUrl,
  remoteIdeHint,
  remoteIdeUrl,
  vscodeRemoteSshUrl,
} from './remote-ide-link';

describe('remote-ide-link (#949)', () => {
  const link = (overrides: Partial<RemoteIdeLink> = {}, gateway: Partial<RemoteIdeLink['gateway']> = {}): RemoteIdeLink => ({
    user: 'hani',
    sshPort: 22,
    path: '/srv/locklane/workareas/1/locklane-949',
    gateway: { productCode: null, buildNumber: null, idePath: null, ...gateway },
    ...overrides,
  });

  it('marks only the two remote entries as remote', () => {
    expect(REMOTE_IDES.map((ide) => ide.id)).toEqual([VSCODE_REMOTE_ID, GATEWAY_REMOTE_ID]);
    expect(REMOTE_IDES.every(isRemoteIde)).toBeTrue();
    expect(isRemoteIde(CODE_SERVER_IDE)).toBeFalse();
    expect(isRemoteIde({ id: 'vscode', label: 'VS Code', desktop: true })).toBeFalse();
  });

  describe('VS Code', () => {
    it('builds the ssh-remote authority from the page host and leaves the default port out', () => {
      expect(vscodeRemoteSshUrl('box.example.com', link())).toBe(
        'vscode://vscode-remote/ssh-remote+hani@box.example.com/srv/locklane/workareas/1/locklane-949',
      );
    });

    it('carries a non-default port', () => {
      expect(vscodeRemoteSshUrl('box', link({ sshPort: 2222 }))).toBe(
        'vscode://vscode-remote/ssh-remote+hani@box:2222/srv/locklane/workareas/1/locklane-949',
      );
    });

    it('percent-encodes path segments', () => {
      expect(vscodeRemoteSshUrl('box', link({ path: '/srv/my repo/wt#1' }))).toBe(
        'vscode://vscode-remote/ssh-remote+hani@box/srv/my%20repo/wt%231',
      );
    });
  });

  describe('JetBrains Gateway', () => {
    const query = (url: string) => {
      expect(url.startsWith('jetbrains-gateway://connect#')).toBeTrue();
      return new URLSearchParams(url.slice('jetbrains-gateway://connect#'.length));
    };

    it('sends type, host, port, user and the url-encoded project path', () => {
      const url = jetbrainsGatewayUrl('box.example.com', link({ sshPort: 2222, path: '/srv/my repo' }));

      expect(url).toContain('projectPath=%2Fsrv%2Fmy+repo');
      const params = query(url);
      expect(params.get('type')).toBe('ssh');
      expect(params.get('host')).toBe('box.example.com');
      expect(params.get('port')).toBe('2222');
      expect(params.get('user')).toBe('hani');
      expect(params.get('projectPath')).toBe('/srv/my repo');
    });

    it('always names the port, 22 included', () => {
      expect(query(jetbrainsGatewayUrl('box', link())).get('port')).toBe('22');
    });

    it('deploys a configured product code and build number', () => {
      const params = query(jetbrainsGatewayUrl('box', link({}, { productCode: 'IU', buildNumber: '241.15989.150' })));

      expect(params.get('deploy')).toBe('true');
      expect(params.get('productCode')).toBe('IU');
      expect(params.get('buildNumber')).toBe('241.15989.150');
      expect(params.has('idePath')).toBeFalse();
    });

    it('uses a configured remote IDE path without deploying, even when a product is also set', () => {
      const params = query(
        jetbrainsGatewayUrl('box', link({}, { productCode: 'IU', buildNumber: '1', idePath: '/opt/idea' })),
      );

      expect(params.get('deploy')).toBe('false');
      expect(params.get('idePath')).toBe('/opt/idea');
      expect(params.has('productCode')).toBeFalse();
      expect(params.has('buildNumber')).toBeFalse();
    });

    it('with nothing configured still builds a deploy=true link, even though it is unusable', () => {
      const params = query(jetbrainsGatewayUrl('box', link()));

      expect(params.get('deploy')).toBe('true');
      expect(params.has('productCode')).toBeFalse();
      expect(params.has('buildNumber')).toBeFalse();
      expect(params.has('idePath')).toBeFalse();
    });

    it('ignores a product code without a build number', () => {
      const params = query(jetbrainsGatewayUrl('box', link({}, { productCode: 'IU' })));

      expect(params.has('productCode')).toBeFalse();
    });
  });

  it('remoteIdeUrl dispatches on the id and answers null for anything else', () => {
    expect(remoteIdeUrl(VSCODE_REMOTE_ID, 'box', link())).toBe(vscodeRemoteSshUrl('box', link()));
    expect(remoteIdeUrl(GATEWAY_REMOTE_ID, 'box', link())).toBe(jetbrainsGatewayUrl('box', link()));
    expect(remoteIdeUrl('code-server', 'box', link())).toBeNull();
    expect(remoteIdeUrl('vscode', 'box', link())).toBeNull();
  });

  it('the hint names the ssh command to try', () => {
    expect(remoteIdeHint('hani', 'box.example.com')).toContain('`ssh hani@box.example.com` works without a password');
    expect(remoteIdeHint('hani', 'box')).toContain('Nothing opened?');
  });

  describe('gatewayLinkIsUsable (#949 follow-up)', () => {
    it('is unusable with nothing configured', () => {
      expect(gatewayLinkIsUsable(link())).toBeFalse();
    });

    it('is usable with only an ide path', () => {
      expect(gatewayLinkIsUsable(link({}, { idePath: '/opt/idea' }))).toBeTrue();
    });

    it('is usable with a product code and build number', () => {
      expect(gatewayLinkIsUsable(link({}, { productCode: 'IU', buildNumber: '241.15989.150' }))).toBeTrue();
    });

    it('is unusable with only a product code', () => {
      expect(gatewayLinkIsUsable(link({}, { productCode: 'IU' }))).toBeFalse();
    });
  });

  it('the unconfigured-Gateway hint names the settings to set', () => {
    expect(gatewayUnconfiguredHint()).toContain('locklane.remote-ide.gateway.ide-path');
    expect(gatewayUnconfiguredHint()).toContain('product-code');
    expect(gatewayUnconfiguredHint()).toContain('build-number');
  });
});
