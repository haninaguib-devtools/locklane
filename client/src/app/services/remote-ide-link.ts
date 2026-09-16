import { InstalledIde } from './default-ide-store';

/**
 * Opening a worktree in a desktop IDE that runs on the *browser's* machine, over SSH to
 * the engine host (#949). Everything here is client-side: the two entries are constants
 * the Settings picker offers only away from `localhost` (a browser on the engine's own
 * machine has the local desktop entries instead), and the links are built here from
 * what the engine reports for a session ({@link RemoteIdeLink}) plus the page's own
 * hostname -- never a request to the engine's open-IDE endpoint, which launches on the
 * engine host.
 *
 * A browser cannot tell whether VS Code or Gateway is installed locally, so there is no
 * detection: the link is opened and a hint says what to install if nothing happened.
 */
export const VSCODE_REMOTE_ID = 'vscode-remote-ssh';
export const GATEWAY_REMOTE_ID = 'jetbrains-gateway-remote-ssh';

export const REMOTE_IDES: readonly InstalledIde[] = [
  { id: VSCODE_REMOTE_ID, label: 'VS Code (remote SSH)', desktop: false, remote: true },
  { id: GATEWAY_REMOTE_ID, label: 'JetBrains Gateway (remote SSH)', desktop: false, remote: true },
];

/** What `GET /api/ides/remote-link` answers for one session -- mirrors `RemoteIdeLinkController.RemoteIdeLink`. */
export interface RemoteIdeLink {
  /** The OS user the engine runs as: the `user` of `ssh user@host`. */
  user: string;
  /** The port to dial; 22 is left out of the VS Code link, which is what VS Code itself defaults to. */
  sshPort: number;
  /** The session's absolute worktree path on the engine host. */
  path: string;
  gateway: {
    productCode: string | null;
    buildNumber: string | null;
    idePath: string | null;
  };
}

/** Whether `ide` is one of the two remote entries, as opposed to code-server or an engine-detected desktop IDE. */
export function isRemoteIde(ide: InstalledIde): boolean {
  return ide.remote === true;
}

/**
 * `vscode://vscode-remote/ssh-remote+<user>@<host>[:<port>]<absolute-path>` -- VS Code's
 * Remote - SSH authority followed by the folder to open. The port rides along only when
 * it is not SSH's default. Each path segment is percent-encoded so a space or `#` in a
 * worktree name survives URL parsing; VS Code decodes them back.
 */
export function vscodeRemoteSshUrl(host: string, link: RemoteIdeLink): string {
  const port = link.sshPort === 22 ? '' : `:${link.sshPort}`;
  return `vscode://vscode-remote/ssh-remote+${link.user}@${host}${port}${encodePath(link.path)}`;
}

/**
 * `jetbrains-gateway://connect#type=ssh&host=…&port=…&user=…&projectPath=…` plus the
 * backend to use: `deploy=false&idePath=<remote path>` when the engine names an IDE
 * already installed on its host, otherwise `deploy=true` with `productCode` and
 * `buildNumber` when both are set (Gateway installs that backend on the host). Neither
 * configured: `deploy=true` alone, and Gateway asks which IDE to deploy. Parameter
 * names per JetBrains' "connect to a remote server from a link" documentation
 * (https://www.jetbrains.com/help/idea/remote-development-a.html#gateway_link).
 */
export function jetbrainsGatewayUrl(host: string, link: RemoteIdeLink): string {
  const params = new URLSearchParams({
    type: 'ssh',
    host,
    port: String(link.sshPort),
    user: link.user,
    projectPath: link.path,
  });
  const { productCode, buildNumber, idePath } = link.gateway;
  if (idePath) {
    params.set('deploy', 'false');
    params.set('idePath', idePath);
  } else {
    params.set('deploy', 'true');
    if (productCode && buildNumber) {
      params.set('productCode', productCode);
      params.set('buildNumber', buildNumber);
    }
  }
  return `jetbrains-gateway://connect#${params.toString()}`;
}

/** The link for a remote entry's id, or null for any other id. */
export function remoteIdeUrl(ideId: string, host: string, link: RemoteIdeLink): string | null {
  switch (ideId) {
    case VSCODE_REMOTE_ID:
      return vscodeRemoteSshUrl(host, link);
    case GATEWAY_REMOTE_ID:
      return jetbrainsGatewayUrl(host, link);
    default:
      return null;
  }
}

/** The dismissable note shown after a remote click (#949): informational, not an error. */
export function remoteIdeHint(user: string, host: string): string {
  return (
    'Nothing opened? Install VS Code with the Remote - SSH extension, or JetBrains Gateway, ' +
    `on this computer, and make sure \`ssh ${user}@${host}\` works without a password.`
  );
}

function encodePath(path: string): string {
  return path
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/');
}
