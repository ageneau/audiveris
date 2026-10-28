# TODO

See [`PLAN.md`](PLAN.md) for the detailed MCP server plan and
[`mcp/INTERFACE.md`](mcp/INTERFACE.md) for the tool contract.

## Next

- [ ] Create the `mcp/` Gradle sub-project with the MCP Java SDK and headless Audiveris bootstrap.
- [ ] Implement session and async-job tools for opening inputs/books, running steps, transcribing, and exporting.
- [ ] Implement inspection tools for sheets, scores, symbols, relations, and constants.
- [ ] Implement headless symbol editing with add/remove/move/link/unlink and undo/redo.
- [ ] Add Streamable HTTP transport with localhost/token security, then tests and documentation.

## Done

- [x] Adopt the existing Audiveris repository; add `AGENTS.md` and `TODO.md`.
