# TODO

See [`PLAN.md`](PLAN.md) for the detailed MCP server plan and
[`mcp/INTERFACE.md`](mcp/INTERFACE.md) for the tool contract.

## Next

- [ ] Add MCP packaging/release documentation and CI smoke coverage.

## Done

- [x] Adopt the existing Audiveris repository; add `AGENTS.md` and `TODO.md`.
- [x] Add the `mcp/` Gradle sub-project, headless bootstrap, STDIO transport, and diagnostics.
- [x] Add session tools for open/close/list/get/save and sheet validity/selection.
- [x] Add async jobs and processing/export/print tools.
- [x] Add score and symbol inspection tools.
- [x] Add constants and all v1 parameters.
- [x] Add core symbol editing: add/remove/move/link/unlink, undo/redo and history.
- [x] Add Streamable HTTP transport with loopback binding, optional bearer token, and `both` mode.
- [x] Add integration tests for transcribe/export and editing.
- [x] Add advanced editing: `update_symbol`, `reprocess_rhythm`, `toggle_tie`.
- [x] Add chord split/merge and system merge.
- [x] Add `set_parameters` for OCR languages and document legacy tessdata.
- [x] Make `Main.getCli()` null-safe for headless embedding.
- [x] Add unit tests for schemas and result envelopes.
