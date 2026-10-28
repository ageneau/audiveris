# TODO

See [`PLAN.md`](PLAN.md) for the detailed MCP server plan and
[`mcp/INTERFACE.md`](mcp/INTERFACE.md) for the tool contract.

## Next

- [ ] Implement constants and remaining parameters.
- [ ] Implement headless symbol editing with add/remove/move/link/unlink and undo/redo.
- [ ] Add Streamable HTTP transport with localhost/token security, then documentation.
- [ ] Add integration tests for open/process/export and editing.

## Done

- [x] Adopt the existing Audiveris repository; add `AGENTS.md` and `TODO.md`.
- [x] Add the `mcp/` Gradle sub-project, headless bootstrap, STDIO transport, and diagnostics.
- [x] Add session tools for open/close/list/get/save and sheet validity/selection.
- [x] Add async jobs and processing/export/print tools.
- [x] Add score and symbol inspection tools.
- [x] Add `set_parameters` for OCR languages and document legacy tessdata.
- [x] Make `Main.getCli()` null-safe for headless embedding.
- [x] Add unit tests for schemas and result envelopes.
