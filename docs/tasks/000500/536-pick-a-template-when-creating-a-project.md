# 536 — Pick a template when creating a project and commit it into the repository
Issue: #536 · Part of: #535

## Asked
Let the operator pick a project template when creating a new project, and have the
engine commit that template's text into the new repository and remember the choice on
the project. A template is one markdown file that describes how a kind of project
should be built; it is written by the operator (or shipped built-in) and read later by
an agent, never executed by the engine. After this task, the add-project dialog's
**create** tab has a "template" pull-down listing the templates found on the engine
host (defaulting to none), the new repository's first push carries the chosen template
at `PROJECT_TEMPLATE.md` in its root, and the project's JSON reports which template it
was created from. Nothing opens a console yet; that is #537. Creation with no template,
and the import tab, are unchanged.

Template layout on the host: `${locklane.data-dir}/templates/<name>/template.md`
(`locklane.data-dir` is `~/.locklane` by default), where `<name>` matches
`^[a-z0-9][a-z0-9-]*$`. The file starts with a YAML frontmatter block carrying `title`
(shown in the pull-down) and `description` (one line, shown as the option's hint); the
body after the frontmatter is the instructions. A small built-in set ships on the
engine classpath under `templates/<name>/template.md` with at least one entry
(`springboot-angular`); a host directory entry with the same `<name>` replaces the
built-in of that name in the listing.

## Done when
- A new authenticated endpoint `GET /api/templates` returns
  `{"templates":[{"name":"…","title":"…","description":"…"},…]}`, sorted by title,
  merged from the classpath built-ins and the host directory (host wins on name
  collision). A missing host directory, an unreadable template, or a directory name
  failing the pattern yields no error: that entry is skipped and the rest are listed.
  Covered by an engine test over a temporary directory. A `@SpringBootTest` route test
  asserts 401 unauthenticated / 200 authenticated, so the `SecurityConfig` matcher for
  `/api/templates` is proven present.
- `POST /api/projects/new` accepts an optional `template` (a name). A name not in the
  listing is rejected with 400 before any repository is created; a name is only ever
  resolved through the listing, never joined onto a path from the request. Covered by
  a `ProjectControllerTest` case.
- On the create path, when `template` is set, the template body is written to
  `PROJECT_TEMPLATE.md` in the checkout root and committed before the first push: in
  the initial commit on the plain `git init` path, and as one additional commit
  `Add project template` on top of the t-workflow installer's output on the bootstrap
  path. Covered by `ProjectCheckoutServiceTest` against the local bare-repo stand-in
  for both paths; the pushed branch's tree contains the file with the body text.
- The `projects` table gains a nullable `template` text column via a new Java
  migration following `V12__AddAccentColorToProjects`, set at creation and returned as
  `template: string | null` on every project JSON. The client `Project` model gains
  the same field.
- The add-project dialog's create tab shows a "template" select above the t-workflow
  checkbox, populated from `GET /api/templates`, with a first option "none" selected by
  default; the import tab has no such control. The chosen name is sent as `template`
  on `createNew`. With zero templates the select shows only "none" and the form
  behaves exactly as today. Covered by a component spec and a `projects.service` spec.
  `app.component.spec.ts` flushes the new request in the specs that mount the real
  popup.
- `README.md` gains one paragraph, next to the install instructions, saying where
  templates live on the host, the `template.md` frontmatter fields, and that a host
  template overrides a built-in of the same name.
- `./mvnw -B test` passes and the client spec suite passes.

## Explicitly not
- Opening a console or feeding the template to an agent — split to #537.
- A template pull-down on the import tab.
- Templates carrying extra files or scripts; the engine copies exactly one markdown
  body.
- Authoring or editing templates from the UI.
- Checking the chosen GitHub account or its scopes: #531 and #532 own that and both
  landed before this one.

## Decisions made along the way
- The listing is read fresh on every `GET /api/templates` and every `find`, not cached
  at boot, mirroring the `gh` accounts listing (#532): an operator can drop a template
  directory in while the engine runs and see it on the next open of the dialog (agent,
  2026-09-01).
- Built-ins are enumerated with Spring's `PathMatchingResourcePatternResolver` over
  `classpath*:templates/*/template.md`, so nested-jar entries resolve in the packaged
  engine; the template's name is the directory segment above `template.md`. One
  built-in ships, `springboot-angular` (agent, per the plan, 2026-09-01).
- Frontmatter is parsed by a small hand parser in `TemplateStore.parse` (leading `---`
  fence, `key: value` lines with optional quotes, closing fence) rather than a new YAML
  dependency; a file with no fence, no closing fence, or no `title` is skipped as
  unreadable and logged at WARN (agent, per the plan, 2026-09-01).
- `ProjectController` resolves the request's `template` through `TemplateStore.find`
  and hands the resolved `ProjectTemplate` (name plus body) to
  `ProjectCheckoutService.createNewProject`; the service never sees request text and
  never touches the store, so its constructor and every test seam stay as they were
  (agent, 2026-09-01).
- The bootstrap path's extra commit `Add project template` runs `git add`/`git commit`
  under the same `BOOTSTRAP_GIT_IDENTITY` environment the installer ran under, because
  the installer's checkout carries no local committer identity of its own (agent,
  2026-09-01).
- `SecurityConfig` gains exactly one matcher, `/api/templates` → authenticated, plus a
  javadoc sentence; a `@SpringBootTest` route test proves it (401 unauthenticated / 200
  with a session). `SecurityConfig` is a reserved future protected surface
  (CONSTITUTION §4.3), not yet enforced by `protected-paths.sh`; named in the plan
  (agent, 2026-09-01).
- The client omits `template` from the request body when none is chosen, the same
  add-only-when-set shape #532 used for `githubLogin`, so every pre-existing exact-body
  assertion stays true (agent, 2026-09-01).
- The chosen template's description is shown as a `.hint` paragraph below the select
  (and as each option's `title` attribute), placed outside the `<label>` so it does not
  inherit the label's uppercase styling — which kept the component CSS untouched
  (agent, 2026-09-01).

## Deviations / notes
- The client `Project` interface gained a required `template: string | null`, so every
  spec that builds a `Project` literal gained `template: null` — ten spec files outside
  the issue's Scope line, all mechanical, named in the plan's Allowed paths as the same
  ripple #427's `accentColor` caused.
- `ProjectRepositoryTest` and `SchemaMigrationTest` gained cases for the new column
  (the issue's Scope says "and their tests"); the migration is `V13`, the next free
  number. #541 also plans a projects migration and must renumber if it lands second —
  named in the plan's overlap section.
