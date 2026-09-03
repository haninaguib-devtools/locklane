# 634 — Fix multi-byte UTF-8 characters split across PTY chunks rendering as � in the console
Issue: #634

## Asked
Stop the console terminal from showing "�" (U+FFFD) in live output. The engine reads the
PTY in fixed-size chunks and `TerminalWebSocketHandler.forward` decoded each chunk on its
own with `new String(chunk, UTF_8)`, so whenever a read boundary fell inside a multi-byte
UTF-8 character (e.g. `─`, three bytes, in a CLI's status rule) the partial bytes on
each side became replacement characters. The bytes on the server were intact — the
replay path decodes the whole buffer at once — only the live push was broken.

## Done when
- `forward` (or the layer it forwards through) keeps per-connection decoder state, so a
  multi-byte sequence split across two PTY reads reaches the client as the correct
  character. Streaming `CharsetDecoder` with carried-over bytes, or binary frames
  decoded by xterm — one chosen and the reason recorded here.
- A unit test feeds a 3-byte character split at byte 1 and at byte 2 across successive
  chunks and asserts the joined output has no U+FFFD and equals the original.
- A unit test asserts a chunk ending on a clean character boundary is delivered
  unchanged and immediately.
- The replay path (`session.bufferedOutput()` on attach) still decodes correctly —
  existing tests pass.
- `./mvnw -B test` passes (modulo the known environmental failures on the dev Mac).
- Human check: Claude Code's bottom status rule renders without "�" across a
  full-width redraw.

## Explicitly not
- Re-evaluating whether #630's `Unicode11Addon` / `allowProposedApi` change is still
  needed once this lands.
- Changing chunk size, fit/resize logic, the PTY size protocol, or `OutputBuffer`.

## Decisions made along the way
- **Streaming `CharsetDecoder` on the engine, not binary frames** (agent, 2026-09-03).
  It keeps the fix to one file and one layer: the client, its message framing, and the
  replay path (`bufferedOutput()` already sends a text frame) all stay exactly as they
  are, and every existing WebSocket test still speaks text. Binary frames would have
  touched two client files and made the client responsible for the same carry-over
  logic in xterm's writer.
- The decoder is a package-visible nested class, `StreamingUtf8Decoder`, inside
  `TerminalWebSocketHandler` rather than a new file, so the diff stays inside the
  issue's named Scope. One instance is created per connection in
  `afterConnectionEstablished` and captured by that connection's subscription.
- `CharsetDecoder.decode(in, out, false)` does the carry-over: with `endOfInput=false`
  an incomplete trailing sequence (at most 3 bytes) is left unread and re-fed with the
  next chunk; genuinely malformed bytes still become U+FFFD (REPLACE), the same as the
  old `new String(...)` behaviour, so a bad byte can never stall the stream.
- A chunk that yields no complete character (e.g. only the first byte of a sequence)
  sends no frame at all, rather than an empty text frame.

## Deviations / notes
- none
