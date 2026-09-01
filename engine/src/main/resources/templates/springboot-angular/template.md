---
title: Spring Boot + Angular
description: A Spring Boot engine serving an Angular single-page client from one runnable jar
---
# Spring Boot + Angular

Build a web application with a Spring Boot back end and an Angular front end, packaged
as a single runnable jar. Work in small, verifiable steps and run the build after each
one.

## Shape

- A Maven multi-module build at the repository root (`pom.xml` with packaging `pom`)
  and two modules: `engine/` (Spring Boot, Java 21) and `client/` (Angular, the current
  stable major, standalone components, strict TypeScript).
- The `client` module builds the Angular app with `frontend-maven-plugin`, installing a
  pinned Node and npm so a plain `./mvnw -B package` works on a machine with only a
  JDK. Its output is packaged as a jar whose `static/` directory the engine serves.
- The `engine` module depends on the `client` artifact and serves it at `/`, with a
  fallback controller that returns `index.html` for any non-`/api` path so deep links
  and page reloads work in the single-page app.
- Include a Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) so nobody needs a
  global Maven.

## Back end

- `spring-boot-starter-web` and `spring-boot-starter-actuator`; expose only `health`
  and `info` on the actuator.
- One example REST endpoint under `/api/` returning JSON, with a unit test for the
  controller and one `@SpringBootTest` proving the application context starts.
- Configuration in `application.yml`; the server port and any secrets come from
  environment variables with sensible local defaults.

## Front end

- An Angular application with routing, one page that calls the example endpoint
  through a service, and a component spec plus a service spec using
  `HttpTestingController`.
- A `proxy.conf.json` so `ng serve` forwards `/api` to the running engine during
  development.
- Lint and format configuration checked in (ESLint, Prettier), run by `npm run lint`.

## Delivery

- A `README.md` with the three commands that matter: build everything
  (`./mvnw -B package`), run the jar, and develop the client against a running engine.
- A `.gitignore` covering Maven `target/`, `node_modules/`, Angular `dist/`, and IDE
  files.
- A GitHub Actions workflow at `.github/workflows/ci.yml` that runs `./mvnw -B test`
  on every push and pull request.

Finish when `./mvnw -B test` passes from a clean checkout and the packaged jar serves
the Angular page, which shows the value returned by the example endpoint.
