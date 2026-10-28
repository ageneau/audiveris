# Plan: native Java MCP server for Audiveris

## Goal

Add a new `mcp/` Gradle sub-project that exposes the Audiveris OMR engine to MCP
clients over STDIO and Streamable HTTP.

The server runs the engine **in-process** (not through the CLI) and supports:

- book/session management
- step-by-step processing and transcription
- MusicXML/PDF export and `.omr` persistence
- inspection of books, sheets, scores, symbols, and relations
- symbol-level OMR editing
- long-running jobs with polling and cancellation

The server is headless: `OMR.gui` stays `null` and no Swing application is launched.

## Constraints found in the codebase

- Audiveris batch operation already works headlessly; most code is guarded by
  `OMR.gui != null`.
- `SheetStub.doOneStep()` has one unguarded call:
  `app/src/main/java/org/audiveris/omr/sheet/SheetStub.java:544` calls
  `Main.getCli().isSave()`. This must be made null-safe.
- `InterController` and `InterEditor` depend on the BSAF application and
  `SheetEditor`, so they cannot be used directly headlessly.
- The underlying `UITask`, `AdditionTask`, `RemovalTask`, `LinkTask`,
  `UnlinkTask`, `RemovalScenario`, `SIGraph`, and `Inter` APIs are public and
  headless-safe.
- `OmrStep.impact(seq, opKind)` updates derived score/rhythm data after an edit;
  this can be replicated headlessly.
- MCP Java SDK: `io.modelcontextprotocol.sdk:mcp:2.1.0`.
- HTTP needs an embedded Servlet 6.1 container: Jetty 12 `ee11`
  (`org.eclipse.jetty.ee11:jetty-ee11-servlet:12.1.14`).

## Module layout

```text
mcp/
├── build.gradle
├── INTERFACE.md
├── README.md
└── src/
    ├── main/
    │   ├── java/org/audiveris/mcp/
    │   │   ├── McpServerMain.java          // args, transport selection, lifecycle
    │   │   ├── HeadlessBootstrap.java      // engine, executors, OCR, logging
    │   │   ├── OmrService.java             // session + processing facade
    │   │   ├── BookSession.java            // Book + paths + locks + edit history
    │   │   ├── JobManager.java             // async jobs, status, cancellation
    │   │   ├── OmrJob.java
    │   │   ├── PathPolicy.java             // workspace/allow-root enforcement
    │   │   ├── edit/
    │   │   │   ├── OmrEditService.java     // headless editing core
    │   │   │   ├── EditHistory.java
    │   │   │   └── HeadlessMoveTask.java   // UITask for geometry changes
    │   │   ├── tools/
    │   │   │   ├── SessionTools.java
    │   │   │   ├── ProcessingTools.java
    │   │   │   ├── InspectionTools.java
    │   │   │   ├── EditingTools.java
    │   │   │   ├── ConstantTools.java
    │   │   │   └── JobTools.java
    │   │   ├── http/HttpTransport.java
    │   │   └── log/JobLogAppender.java
    │   └── resources/logback.xml            // console to stderr
    └── test/java/org/audiveris/mcp/...
```

## Bootstrap and process safety

`HeadlessBootstrap` will:

1. Configure `java.awt.headless=true` in JVM args.
2. Point Logback at the MCP configuration **before** loading Audiveris classes.
3. Save the original `System.out`, then redirect `System.out` to `System.err`.
   This prevents Audiveris output from corrupting the STDIO protocol.
4. Pass the original stdout explicitly to `StdioServerTransportProvider`.
5. Initialize:
   - `WellKnowns.ensureLoaded()`
   - `OmrExecutors.restart()`
   - `OMR.engine = BookManager.getInstance()`
   - `Languages.getInstance().checkSupport()`
6. Never load `MainGui`, `SheetEditor`, or `StubsController`.

## Session and job model

- One `BookSession` per opened input/book:
  - UUID `book_id`
  - `Book`, input path, target `.omr` path
  - per-book read/write lock
  - edit history (undo/redo)
- Engine operations go through a serialized executor by default
  (`max_jobs=1`) because Audiveris globals and output paths are shared.
- Long operations are async:
  - `run_step`, `transcribe`, `export_musicxml`, `print_book`,
    `annotate_book`, `sample_book`
  - return a `job_id`
  - poll with `get_job`
  - `cancel_job` sets `book.setPauseRequired(true)` and cancels the future
- Job progress:
  - a job-scoped Logback appender captures log events into a ring buffer
  - parses step messages to update `current_step` / `current_sheet`
  - optionally forwards selected messages as MCP logging notifications

## MCP tool surface

### Diagnostics

- `version` — Audiveris/MCP/Java/Tesseract versions
- `list_steps` — OMR pipeline with descriptions
- `list_ocr_languages`
- `list_shapes` — shape names, optionally filtered by category
- `list_relation_types`

### Sessions and books

- `open_input(path, output_dir?, save_book?)`
- `open_book(path)`
- `close_book(book_id, save=true)`
- `list_books`
- `get_book(book_id)`
- `get_sheets(book_id)`
- `get_sheet(book_id, sheet)`
- `get_score(book_id)` — movements, parts, pages, measures, key/time data
- `save_book(book_id, path?)`
- `upgrade_book(book_id)`
- `set_sheet_selection(book_id, spec)`
- `set_sheet_validity(book_id, sheet, valid)`
- `set_parameters(book_id, sheet?, ...)`
- `get_constants(prefix?)`
- `set_constant(qname, value)`
- `reset_constant(qname)`

### Processing

- `run_step(book_id, step, sheets?, force?, save?, swap?)`
- `transcribe(book_id, sheets?, save?, swap?)`
- `export_musicxml(book_id, output_path?, sheets?, format="mxl")`
- `print_book(book_id, output_path?, sheets?)`
- `annotate_book(book_id, sheets?)`
- `sample_book(book_id, sheets?)`
- `get_job(job_id)`
- `list_jobs`
- `cancel_job(job_id)`
- `get_log(book_id?, job_id?, lines=200)`

### Symbol inspection and editing

- `list_symbols(book_id, sheet, system?, shape?, bbox?, limit?)`
- `get_symbol(book_id, sheet, inter_id)`
- `add_symbol(book_id, sheet, shape, x, y, system_id?, staff_id?, link=true)`
- `remove_symbols(book_id, sheet, inter_ids[])`
- `move_symbol(book_id, sheet, inter_id, x, y, snap=true)`
- `update_symbol(book_id, sheet, inter_id, ...)` — safe property updates
- `link_symbols(book_id, sheet, source_id, target_id, relation_type)`
- `unlink_symbols(book_id, sheet, relation_id? or source_id/target_id/relation_type)`
- `undo_edit(book_id, sheet)`
- `redo_edit(book_id, sheet)`
- `get_edit_history(book_id, sheet)`
- `reprocess_rhythm(book_id, sheet, stack_id?/page?)`

Deferred to phase 2:

- split/merge chords
- merge systems
- toggle tie
- specialized sentence/word/metronome edits beyond simple property updates

## Headless editing design

`OmrEditService` mirrors the safe part of `InterController` without `Task`/UI:

1. Build a `UITaskList`.
2. Apply `performDo()` or `performUndo()` atomically.
3. On failure, undo already-performed tasks in reverse order.
4. Mark the sheet/book modified.
5. Compute the first impacted step using the same logic as
   `InterController.CtrlTask`.
6. Call `OmrStep.impact(seq, opKind)` for the impacted range.
7. Record the `UITaskList` in an `EditHistory` owned by `BookSession`.

Operation details:

- **Add**: `InterFactory.createManual(shape, sheet)` → choose staff via
  `StaffManager.getStavesOf/getClosestStaff` →
  `deriveFrom(symbol, sheet, font, point)` → `inter.preAdd(...)` → perform tasks.
- **Remove**: `RemovalScenario.populate(inters, seq)` → perform. `rebuildGlyphs`
  is private and UI-oriented; v1 leaves removed glyphs as free glyphs. A later
  phase can extract a public glyph-rebuild helper.
- **Move**: custom `HeadlessMoveTask` storing old/new bounds, old/new glyph,
  and links/unlinks; performs bounds/glyph/link changes directly. This avoids
  `DefaultEditor`, which requires `SheetEditor`.
- **Link/unlink**: `LinkTask`, `UnlinkTask`.
- **Relations**: resolve relation class under `org.audiveris.omr.sig.relation`,
  instantiate, validate.
- **Undo/redo**: replay `UITaskList` and reapply impacts with
  `OpKind.UNDO` / `OpKind.REDO`.

## Build and launch

Modify `settings.gradle`:

- include `mcp` in the non-Flatpak branch.

`mcp/build.gradle`:

- plugins: `audiveris-commons`, `application`
- `implementation project(':app')`
- `implementation 'io.modelcontextprotocol.sdk:mcp:2.1.0'`
- `implementation 'org.eclipse.jetty.ee11:jetty-ee11-servlet:12.1.14'`
- likely `implementation 'org.jgrapht:jgrapht-core:1.5.2'` and
  `slf4j-api` for the compile classpath
- shared JVM args:
  - `--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED`
  - `--enable-native-access=ALL-UNNAMED`
  - `-Djava.awt.headless=true`
  - UTF-8 properties
- `run { workingDir = rootProject.file('app') }` so development mode finds
  `app/res`
- tests use JUnit 4.13.2

Launch:

```sh
./gradlew :mcp:run --args="--transport stdio"
./gradlew :mcp:run --args="--transport http --http-port 8765"
./gradlew :mcp:installDist
```

## HTTP mode

CLI options/environment:

- `--transport stdio|http|both`
- `--http-host` default `127.0.0.1`
- `--http-port` default `8765`
- `--http-path` default `/mcp`
- `--http-token` or `AUDIVERIS_MCP_TOKEN`
- `--workspace` (default under Audiveris user data)
- `--allow-root` repeatable
- `--allow-any-path`

Security:

- Bind loopback by default.
- Refuse non-loopback bind without a token.
- Require `Authorization: Bearer <token>` when configured.
- Enforce canonical path containment for HTTP file access.
- Limit request size and concurrent engine jobs.
- STDIO is treated as local/trusted, but the same path policy can be enabled.

Jetty setup:

- `HttpServletStreamableServerTransportProvider` registered in a
  `ServletContextHandler`.
- `McpServer.sync(transportProvider)` per transport.
- In `both` mode, two `McpSyncServer` instances share the same tool registry
  and service layer.
- Graceful shutdown closes servers, jobs, sessions, and `OmrExecutors`.

## Tests

- Unit tests:
  - path policy / traversal rejection
  - job state transitions and cancellation
  - edit history / undo-redo sequencing
  - JSON schema/tool argument validation
- Integration tests:
  - bootstrap headlessly
  - open `data/examples/cucaracha.png`, create stubs, save `.omr`, reload it
  - opt-in full transcription/export test producing `.mxl`
  - opt-in editing test: process a small score, list symbols, add/remove/move
    one, undo, redo, export
- MCP smoke tests:
  - run STDIO server and drive it with a minimal MCP client/inspector
  - run HTTP server and verify initialize/tools/list/tools/call

## Documentation

- `mcp/INTERFACE.md`: normative tool contract, schemas, examples, validation.
- `mcp/README.md`:
  - prerequisites (Java 25, Tesseract languages)
  - build/run commands
  - client config examples for STDIO and HTTP
  - environment variables and security notes
  - supported editing operations and current limitations
- Update `project-structure.md` and `README.md` to mention the `mcp`
  sub-project.

## Implementation order

1. Scaffold module, bootstrap, STDIO server, version/list tools, logging isolation.
2. Session manager, open/close/list/get book/sheet, save.
3. Async jobs, step/transcribe/export/print, progress and cancellation.
4. Inspection: score, sheets, symbols, relations, constants, parameters.
5. Headless editing core + add/remove/move/link/unlink/undo/redo.
6. HTTP transport, token auth, path policy, `both` mode.
7. Tests, docs, CI integration.

## Prerequisites

- One minimal app change: null-safe `Main.getCli()` in
  `SheetStub.doOneStep()`.
- No symbol glyph rebuild on removal in v1; removed symbols leave free glyphs.
- Specialized UI actions (split/merge chords, merge systems, toggle tie) are
  phase 2 unless needed earlier.
