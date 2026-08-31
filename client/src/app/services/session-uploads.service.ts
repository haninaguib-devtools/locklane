import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Uploads a file the user dropped or pasted onto a console terminal (#436) to the
 * engine, which writes it to disk next to the session and answers with the
 * server-side path — the piece a browser drop can never provide by itself, since a
 * page only ever receives a file's contents. The terminal then injects that path
 * into the PTY as a bracketed paste, so to the CLI it looks like a file dragged
 * into a native terminal.
 */
@Injectable({ providedIn: 'root' })
export class SessionUploadsService {
  private readonly http = inject(HttpClient);

  /** Resolves to the absolute server-side path the engine stored the file at. */
  upload(sessionId: string, file: File): Observable<{ path: string }> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<{ path: string }>(`/api/sessions/${sessionId}/uploads`, form);
  }
}
