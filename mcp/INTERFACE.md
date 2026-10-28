# Audiveris MCP interface

Interface version: **1.0**

This document is the normative contract for the Audiveris MCP server. It defines
the transports, result envelope, identifiers, shared types, tools, and validation
rules. The implementation must generate MCP tool input schemas that match the
tables below.

See [`../PLAN.md`](../PLAN.md) for the architecture and implementation plan.

## 1. Scope

The server is local-first and exposes the Audiveris OMR engine:

- open inputs and `.omr` books
- run steps and transcribe
- export MusicXML and PDF
- inspect books, sheets, scores, symbols, and relations
- edit symbols and relations
- inspect and change constants
- run long operations as asynchronous jobs

V1 is tools-only. MCP resources and prompts are not exposed. Output files are
returned as absolute local paths.

## 2. Transports

- **STDIO** (default): newline-delimited JSON-RPC over stdin/stdout.
  All non-protocol stdout is redirected to stderr.
- **Streamable HTTP**: endpoint `/mcp` by default. It binds to loopback by default;
  a non-loopback bind requires a bearer token. Clients send
  `Authorization: Bearer <token>` when a token is configured.
- **Both**: one process may serve STDIO and HTTP simultaneously.

All JSON is UTF-8. Timestamps are ISO-8601 UTC strings, for example
`2026-10-28T12:34:56Z`.

## 3. Result envelope

Every tool returns one JSON object in `content[0].text`. When the MCP SDK
supports structured content, the same object is also returned in
`structuredContent`.

### Success

```json
{
  "ok": true,
  "data": {},
  "warnings": []
}
```

- `ok` — required boolean.
- `data` — required object, specific to the tool.
- `warnings` — optional array of warning objects.

Warning object:

```json
{
  "code": "GLYPH_LEFT_AS_FREE",
  "message": "Removed inter #77; its glyph remains as a free glyph."
}
```

### Failure

Tool failures set MCP `isError` to `true`.

```json
{
  "ok": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Inter 77 not found on sheet 1",
    "details": {
      "book_id": "3f6f0f0e-7f9b-4a6e-9e0f-2f8c1d4a7b11",
      "sheet": 1,
      "inter_id": 77
    }
  }
}
```

### Error codes

| Code | Meaning |
| :--- | :--- |
| `INVALID_ARGUMENT` | Malformed input, bad enum, bad range. |
| `NOT_FOUND` | Book, sheet, system, staff, inter, relation, or job not found. |
| `CONFLICT` | Duplicate relation, duplicate session, edit history conflict. |
| `UNSUPPORTED` | Valid request not supported in v1. |
| `PERMISSION_DENIED` | Path outside allowed roots or token missing. |
| `JOB_BUSY` | Engine queue full or book already processing. |
| `CANCELLED` | Job cancelled. |
| `IO_ERROR` | File or `.omr` I/O failure. |
| `ENGINE_ERROR` | Audiveris engine failure. |
| `INTERNAL` | Unexpected server failure. |

Protocol-level errors (unknown method, malformed JSON-RPC) use standard MCP
error codes.

## 4. Identifiers and paths

| Name | Type | Description |
| :--- | :--- | :--- |
| `book_id` | string | UUID assigned when a book is opened. Stable for the session. |
| `sheet` | integer | 1-based sheet number within a book. |
| `system_id` | integer | Audiveris `SystemInfo` ID. Unique within a sheet. |
| `staff_id` | integer | Audiveris `Staff` ID. Unique within a sheet. |
| `inter_id` | integer | Audiveris `Inter` ID. Unique within a sheet. |
| `glyph_id` | integer | Audiveris `Glyph` ID. Unique within a sheet. |
| `page_id` | integer | Audiveris `Page` ID. Unique within a sheet. |
| `stack_id` | integer | Audiveris `MeasureStack` ID. Unique within a system. |
| `job_id` | string | UUID assigned to an asynchronous job. |
| `relation_id` | string | Opaque relation handle, valid for the current session. |

### Paths

- Relative input and output paths are resolved against `--workspace`.
- STDIO mode allows absolute paths anywhere by default.
- HTTP mode allows paths only under the workspace and configured `--allow-root`
  directories, unless `--allow-any-path` is set.
- Paths are normalized and checked after resolving `..`. Symlinks are resolved
  when the path exists.
- Output paths must be writable. Input paths must be readable.
- `.omr` outputs use the `.omr` extension. If `save_book` is true and no output
  directory is given, the server uses `<workspace>/books/<radix>.omr`.

### Sheet specification

The `sheets` parameter accepts one of:

- an integer: `1`
- an array of integers: `[1, 3, 4]`
- an Audiveris natural spec string: `"1,3-5"`
- omitted: the book selection if set, otherwise all valid sheets

Invalid sheet numbers produce `INVALID_ARGUMENT`. Invalid or removed sheets are
skipped with a warning.

## 5. Shared types

### BookSummary

```json
{
  "book_id": "3f6f0f0e-7f9b-4a6e-9e0f-2f8c1d4a7b11",
  "radix": "cucaracha",
  "input_path": "/abs/path/cucaracha.png",
  "book_path": "/abs/path/cucaracha.omr",
  "version": "5.12.0",
  "sheet_count": 1,
  "valid_sheet_count": 1,
  "score_count": 1,
  "modified": false,
  "dirty": false,
  "multi_sheet": false,
  "multi_movement": false,
  "reused": false,
  "created_at": "2026-10-28T12:00:00Z",
  "last_accessed_at": "2026-10-28T12:05:00Z"
}
```

`score_count` is 0 until transcription has produced scores.

### SheetSummary

```json
{
  "sheet": 1,
  "valid": true,
  "modified": false,
  "current_step": null,
  "latest_step": "PAGE",
  "steps_done": ["LOAD", "BINARY", "..."],
  "width": 1000,
  "height": 1400,
  "interline": 20,
  "input": {
    "path": "/abs/path/cucaracha.png",
    "page_index": 0
  },
  "pages": [
    {
      "page_id": 1,
      "movement_start": true,
      "measure_count": 8
    }
  ],
  "systems": [
    {
      "system_id": 1,
      "page_id": 1,
      "staff_ids": [1, 2],
      "bounds": [20, 100, 900, 300],
      "measure_count": 4
    }
  ]
}
```

`current_step` is non-null only while a step is running.

### ScoreSummary

```json
{
  "score_index": 0,
  "score_id": 1,
  "page_count": 1,
  "parts": [
    {
      "part_id": 1,
      "logical_part_id": 1,
      "name": "Piano",
      "abbreviation": "Pno.",
      "staff_ids": [1, 2]
    }
  ],
  "pages": [
    {
      "page_id": 1,
      "sheet": 1,
      "measure_count": 8,
      "first_system_id": 1,
      "last_system_id": 1
    }
  ],
  "systems": [
    {
      "system_id": 1,
      "page_id": 1,
      "staff_ids": [1, 2],
      "key": {"fifths": 0, "mode": "major"},
      "time": {"numerator": 4, "denominator": 4},
      "measure_count": 4
    }
  ],
  "measures": [],
  "measure_range": {"first": 1, "last": 8}
}
```

`measures` is populated only when `include_measures` is true.

### SymbolSummary

```json
{
  "inter_id": 77,
  "system_id": 1,
  "staff_id": 3,
  "part_id": 1,
  "class": "HeadInter",
  "shape": "NOTEHEAD_BLACK",
  "bounds": [300, 400, 16, 12],
  "center": [308, 406],
  "grade": 0.97,
  "contextual_grade": 0.99,
  "manual": false,
  "removed": false,
  "frozen": false,
  "glyph_id": 1234,
  "attributes": {},
  "relations": []
}
```

`attributes` contains class-specific data when requested:

- pitched symbols: `pitch`, `voice_id`
- text symbols: `text`, `role`, `language`, `syllable`
- time symbols: `numerator`, `denominator`
- metronome symbols: `beat_unit`, `per_minute`
- number symbols: `value`
- chords: `voice_id`, `member_ids`

### RelationSummary

```json
{
  "relation_id": "r:1:77:StemHeadRelation:90",
  "source_id": 77,
  "target_id": 90,
  "type": "StemHeadRelation",
  "class": "org.audiveris.omr.sig.relation.StemHeadRelation",
  "grade": 0.95
}
```

`relation_id` is an opaque handle valid for the session. It may change after
edits. Prefer `source_id`, `target_id`, and `type` for deterministic unlinking.

### Job

```json
{
  "job_id": "8d1c2a44-1c6b-4f0e-9d77-55a0d5f2c111",
  "type": "transcribe",
  "state": "RUNNING",
  "book_id": "3f6f0f0e-7f9b-4a6e-9e0f-2f8c1d4a7b11",
  "created_at": "2026-10-28T12:00:00Z",
  "started_at": "2026-10-28T12:00:01Z",
  "ended_at": null,
  "cancel_requested": false,
  "progress": {
    "current_step": "HEADS",
    "current_sheet": 1,
    "sheets_done": 0,
    "sheets_total": 1,
    "percent": 55
  },
  "result": null,
  "error": null,
  "log_tail": [
    {
      "ts": "2026-10-28T12:00:10Z",
      "level": "INFO",
      "logger": "org.audiveris.omr.sheet.SheetStub",
      "message": "Sheet#1 scheduling [HEADS, STEMS]"
    }
  ]
}
```

Job states: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`.

`percent` is an estimate and may be omitted.

### ConstantSummary

```json
{
  "qname": "org.audiveris.omr.sheet.BookManager.useCompression",
  "type": "Boolean",
  "value": "true",
  "source_value": "true",
  "unit": "",
  "description": "Should we use compression (to .MXL files) or not (to .XML files)."
}
```

### LogEntry

```json
{
  "ts": "2026-10-28T12:00:10Z",
  "level": "INFO",
  "logger": "org.audiveris.omr.sheet.Book",
  "message": "Book reaching PAGE on sheets:[#1]"
}
```

## 6. Tool catalog

All tool names are lowercase snake_case. Input objects reject unknown fields
unless stated otherwise.

| Tool | Async | Read-only | Destructive |
| :--- | :---: | :---: | :---: |
| `version` | no | yes | no |
| `list_steps` | no | yes | no |
| `list_ocr_languages` | no | yes | no |
| `list_shapes` | no | yes | no |
| `list_relation_types` | no | yes | no |
| `open_input` | no | no | no |
| `open_book` | no | no | no |
| `close_book` | no | no | yes |
| `list_books` | no | yes | no |
| `get_book` | no | yes | no |
| `get_sheets` | no | yes | no |
| `get_sheet` | no | yes | no |
| `get_score` | no | yes | no |
| `save_book` | no | no | no |
| `set_sheet_selection` | no | no | no |
| `set_sheet_validity` | no | no | yes |
| `set_parameters` | no | no | yes |
| `get_constants` | no | yes | no |
| `set_constant` | no | no | yes |
| `reset_constant` | no | no | yes |
| `run_step` | yes | no | yes |
| `transcribe` | yes | no | yes |
| `export_musicxml` | yes | no | no |
| `print_book` | yes | no | no |
| `annotate_book` | yes | no | no |
| `sample_book` | yes | no | no |
| `upgrade_book` | yes | no | yes |
| `get_job` | no | yes | no |
| `list_jobs` | no | yes | no |
| `cancel_job` | no | no | yes |
| `get_log` | no | yes | no |
| `list_symbols` | no | yes | no |
| `get_symbol` | no | yes | no |
| `add_symbol` | no | no | no |
| `remove_symbols` | no | no | yes |
| `move_symbol` | no | no | yes |
| `update_symbol` | no | no | yes |
| `link_symbols` | no | no | yes |
| `unlink_symbols` | no | no | yes |
| `undo_edit` | no | no | yes |
| `redo_edit` | no | no | yes |
| `get_edit_history` | no | yes | no |
| `reprocess_rhythm` | no | no | yes |
| `toggle_tie` | no | no | yes |
| `merge_chords` | no | no | yes |
| `split_chord` | no | no | yes |
| `merge_systems` | no | no | yes |

"Async" tools return `{"job_id": "..."}` immediately. Poll with `get_job`.

## 7. Diagnostics tools

### `version`

Input: `{}`

Result:

```json
{
  "interface_version": "1.0",
  "audiveris": "5.12.0",
  "audiveris_build": "c4ef1052a",
  "mcp_server": "1.0.0",
  "java": "25.0.4",
  "os": "Linux 6.8",
  "architecture": "amd64",
  "ocr_engine": "Tesseract OCR, version 5.5.2"
}
```

### `list_steps`

Input: `{}`

Result:

```json
{
  "steps": [
    {
      "name": "LOAD",
      "description": "Get the sheet gray picture",
      "tip": "Load the sheet image",
      "parallel": false,
      "first": true,
      "last": false
    }
  ]
}
```

`name` is one of: `LOAD`, `BINARY`, `SCALE`, `GRID`, `HEADERS`, `STEM_SEEDS`,
`BEAMS`, `LEDGERS`, `HEADS`, `STEMS`, `REDUCTION`, `CUE_BEAMS`, `TEXTS`,
`MEASURES`, `CHORDS`, `CURVES`, `SYMBOLS`, `LINKS`, `RHYTHMS`, `PAGE`.

### `list_ocr_languages`

Input: `{}`

Result:

```json
{
  "installed": ["eng", "fra"],
  "default": "eng"
}
```

Only installed languages are returned. The server does not download languages.

### `list_shapes`

Input:

```json
{
  "category": "heads",
  "manual_only": false
}
```

Both fields are optional. `category` is one of `heads`, `rests`, `clefs`,
`keys`, `times`, `notes`, `text`, `dynamics`, `ornaments`, `articulations`,
`barlines`, `beams`, `curves`, `other`.

Result:

```json
{
  "shapes": [
    {
      "name": "NOTEHEAD_BLACK",
      "categories": ["heads", "notes"],
      "manual_add": true
    }
  ]
}
```

`name` is an `org.audiveris.omr.glyph.Shape` enum name. `manual_add` reports
whether `InterFactory.createManual` supports the shape.

### `list_relation_types`

Input: `{}`

Result:

```json
{
  "relations": [
    {
      "name": "StemHeadRelation",
      "class": "org.audiveris.omr.sig.relation.StemHeadRelation",
      "directed": true
    }
  ]
}
```

Only relation classes under `org.audiveris.omr.sig.relation` are listed and
accepted by `link_symbols`.

## 8. Session and book tools

### `open_input`

Input:

```json
{
  "path": "data/examples/cucaracha.png",
  "output_dir": "output",
  "save_book": true
}
```

- `path` — required image or PDF path.
- `output_dir` — optional directory for the `.omr` output.
- `save_book` — default `true`; create the `.omr` immediately after stubs.

Result:

```json
{
  "book": { "book_id": "...", "radix": "cucaracha", "sheet_count": 1 },
  "reused": false
}
```

If the canonical input path already has an open session, the existing session is
returned with `reused: true`.

### `open_book`

Input:

```json
{
  "path": "output/cucaracha.omr",
  "auto_upgrade": false
}
```

- `path` — required `.omr` path.
- `auto_upgrade` — default `false`; when true, schedule `upgrade_book`.

Result: same shape as `open_input`.

### `close_book`

Input:

```json
{
  "book_id": "...",
  "save": true
}
```

Result:

```json
{
  "book_id": "...",
  "closed": true,
  "saved": true
}
```

Closing cancels running jobs for the book. If `save` is true and the book is
modified, it is stored before closing.

### `list_books`

Input: `{}`

Result:

```json
{
  "books": [
    {
      "book_id": "...",
      "radix": "cucaracha",
      "input_path": "...",
      "book_path": "...",
      "sheet_count": 1,
      "score_count": 1,
      "modified": false
    }
  ]
}
```

### `get_book`

Input:

```json
{
  "book_id": "..."
}
```

Result: `BookSummary`.

### `get_sheets`

Input:

```json
{
  "book_id": "..."
}
```

Result:

```json
{
  "sheets": [
    {
      "sheet": 1,
      "valid": true,
      "latest_step": "PAGE",
      "modified": false,
      "input_path": "..."
    }
  ]
}
```

### `get_sheet`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "include_systems": true,
  "include_staves": true,
  "include_symbols": false,
  "symbol_limit": 200
}
```

Result: `SheetSummary`. When `include_staves` is true, each system also has a
`staves` array:

```json
{
  "staff_id": 1,
  "part_id": 1,
  "bounds": [20, 100, 900, 40],
  "interline": 20
}
```

When `include_symbols` is true, the result also contains `symbols` as an array of
`SymbolSummary`, limited by `symbol_limit`.

### `get_score`

Input:

```json
{
  "book_id": "...",
  "score_index": null,
  "include_measures": false,
  "measure_limit": 500
}
```

- Omit `score_index` to return all scores.
- `include_measures` default `false`.
- `measure_limit` default 500, max 5000.

Result:

```json
{
  "scores": [ { "score_index": 0, "..." : "ScoreSummary" } ]
}
```

Measure object when requested:

```json
{
  "measure_id": 12,
  "page_id": 1,
  "system_id": 1,
  "stack_id": 1,
  "number": "1",
  "start": true,
  "key": {"fifths": 0, "mode": "major"},
  "time": {"numerator": 4, "denominator": 4}
}
```

### `save_book`

Input:

```json
{
  "book_id": "...",
  "path": "output/cucaracha.omr"
}
```

`path` is optional. Result:

```json
{
  "book_id": "...",
  "book_path": "/abs/path/output/cucaracha.omr",
  "saved": true
}
```

### `set_sheet_selection`

Input:

```json
{
  "book_id": "...",
  "sheets": "1,3-5"
}
```

Result:

```json
{
  "book_id": "...",
  "selection": "1,3-5"
}
```

The selection applies to later tools that omit `sheets`.

### `set_sheet_validity`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "valid": false
}
```

Result: updated `SheetSummary`.

### `set_parameters`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "parameters": {
    "interline": 20,
    "beam": 4,
    "input_quality": "Standard",
    "music_family": "Bravura",
    "text_family": "Serif",
    "ocr_languages": "eng",
    "processing_switches": {
      "tremolos": true,
      "cue_beams": false
    }
  }
}
```

`sheet` is optional. Supported v1 keys:

| Key | Type | Scope |
| :--- | :--- | :--- |
| `ocr_languages` | string | sheet/book |
| `interline` | positive integer | sheet/book |
| `beam` | positive integer | sheet/book |
| `input_quality` | `Synthetic`, `Standard`, `Poor` | sheet/book |
| `music_family` | string | sheet/book |
| `text_family` | string | sheet/book |
| `processing_switches` | object of booleans | sheet/book |

Result:

```json
{
  "applied": ["interline", "processing_switches.tremolos"],
  "warnings": []
}
```

Unknown keys return `UNSUPPORTED`. Invalid values return `INVALID_ARGUMENT`.

### `get_constants`

UI-only constants are not exposed headlessly. Constants containing personal file
history (`bookHistory`, `imageHistory`) are hidden.

Input:

```json
{
  "prefix": "org.audiveris.omr.sheet",
  "limit": 100,
  "offset": 0
}
```

All fields optional. `limit` default 100, max 1000.

Result:

```json
{
  "total": 42,
  "offset": 0,
  "limit": 100,
  "has_more": false,
  "constants": [ { "qname": "...", "..." : "ConstantSummary" } ]
}
```

### `set_constant`

Input:

```json
{
  "qname": "org.audiveris.omr.sheet.BookManager.useCompression",
  "value": "true"
}
```

Result:

```json
{
  "constant": { "qname": "...", "value": "true", "source_value": "true" },
  "old_value": "false"
}
```

Constants are process-global. Changes affect later processing and may not affect
already-loaded step classes.

### `reset_constant`

Input:

```json
{
  "qname": "org.audiveris.omr.sheet.BookManager.useCompression"
}
```

Result: updated `ConstantSummary`.

## 9. Processing tools

All async tools return:

```json
{
  "job_id": "..."
}
```

### `run_step`

Input:

```json
{
  "book_id": "...",
  "step": "GRID",
  "sheets": [1],
  "force": false,
  "save": false,
  "swap": true
}
```

- `step` — required step name.
- `sheets` — optional sheet specification.
- `force` — default `false`; reset and reprocess if the step is already done.
- `save` — default `false`; store the book after the step.
- `swap` — default `true`; store and dispose sheet material after processing.

Job result:

```json
{
  "book_id": "...",
  "step": "GRID",
  "sheets": [1],
  "ok": true
}
```

### `transcribe`

Input:

```json
{
  "book_id": "...",
  "sheets": [1],
  "save": true,
  "swap": true
}
```

Job result:

```json
{
  "book_id": "...",
  "scores": 1,
  "pages": 1,
  "ok": true
}
```

### `export_musicxml`

Input:

```json
{
  "book_id": "...",
  "output_path": "output/cucaracha",
  "sheets": [1],
  "format": "mxl",
  "ensure_transcribed": true
}
```

- `output_path` — optional path with or without extension.
- `format` — `mxl` (default) or `xml`.
- `ensure_transcribed` — default `true`; transcribe first if needed.

Job result:

```json
{
  "outputs": [
    {
      "kind": "musicxml",
      "path": "/abs/path/output/cucaracha.mxl",
      "format": "mxl",
      "score_index": 0
    }
  ]
}
```

Multi-movement books produce one output per movement, using the `.mvt#` suffix.

### `print_book`

Input:

```json
{
  "book_id": "...",
  "output_path": "output/cucaracha-print.pdf",
  "sheets": [1]
}
```

Job result:

```json
{
  "outputs": [
    {
      "kind": "pdf",
      "path": "/abs/path/output/cucaracha-print.pdf"
    }
  ]
}
```

Printing depends on the Audiveris PDF renderer. If it does not produce a valid
PDF header, the job fails with `IO_ERROR`.

### `annotate_book`

Input:

```json
{
  "book_id": "...",
  "sheets": [1]
}
```

Job result:

```json
{
  "outputs": [
    {
      "kind": "annotation_zip",
      "path": "/abs/path/output/cucaracha.annotations.zip"
    }
  ]
}
```

### `sample_book`

Input:

```json
{
  "book_id": "...",
  "sheets": [1]
}
```

Job result:

```json
{
  "sample_count": 123
}
```

### `upgrade_book`

Input:

```json
{
  "book_id": "..."
}
```

Job result:

```json
{
  "upgraded_sheets": [1, 2]
}
```

### `get_job`

Input:

```json
{
  "job_id": "...",
  "log_lines": 50
}
```

`log_lines` default 50, max 500.

Result: `Job`.

### `list_jobs`

Input:

```json
{
  "book_id": "...",
  "state": "RUNNING",
  "limit": 20
}
```

All fields optional. Result:

```json
{
  "jobs": [ { "job_id": "...", "type": "transcribe", "state": "RUNNING" } ]
}
```

### `cancel_job`

Input:

```json
{
  "job_id": "..."
}
```

Result:

```json
{
  "job_id": "...",
  "cancel_requested": true
}
```

Cancellation is cooperative. The job stops at the next step boundary.

### `get_log`

Input:

```json
{
  "book_id": "...",
  "job_id": null,
  "lines": 200,
  "level": "INFO"
}
```

Exactly one of `book_id` or `job_id` is required. `lines` default 200, max 2000.
`level` is an optional minimum level: `DEBUG`, `INFO`, `WARN`, `ERROR`.

Result:

```json
{
  "lines": [ { "ts": "...", "level": "INFO", "logger": "...", "message": "..." } ]
}
```

## 10. Inspection tools

### `list_symbols`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "system_id": 1,
  "staff_id": 3,
  "shape": "NOTEHEAD_BLACK",
  "inter_class": "HeadInter",
  "bbox": {"x": 0, "y": 0, "width": 1000, "height": 1400},
  "include_relations": false,
  "include_attributes": false,
  "limit": 200,
  "offset": 0
}
```

All filters except `book_id` and `sheet` are optional. `limit` default 200, max
1000. `offset` default 0.

Result:

```json
{
  "total": 1,
  "offset": 0,
  "limit": 200,
  "has_more": false,
  "symbols": [ { "inter_id": 77, "..." : "SymbolSummary" } ]
}
```

### `get_symbol`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "inter_id": 77,
  "include_relations": true,
  "include_attributes": true,
  "include_glyph": false
}
```

Result:

```json
{
  "symbol": { "inter_id": 77, "..." : "SymbolSummary" }
}
```

When `include_glyph` is true, `symbol` also contains:

```json
{
  "glyph": {
    "glyph_id": 1234,
    "bounds": [300, 400, 16, 12],
    "weight": 120
  }
}
```

## 11. Editing tools

Editing operates on one sheet at a time and is serialized with processing.

Implemented v1: `add_symbol`, `remove_symbols`, `move_symbol`, `update_symbol`,
`link_symbols`, `unlink_symbols`, `toggle_tie`, `reprocess_rhythm`,
`merge_chords`, `split_chord`, `merge_systems`, `undo_edit`, `redo_edit`,
`get_edit_history`.

Reserved for later: cross-system links and glyph pixel rebuilding.

### `add_symbol`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "shape": "NOTEHEAD_BLACK",
  "x": 320,
  "y": 410,
  "system_id": null,
  "staff_id": 3,
  "snap": true,
  "link": true
}
```

- `shape` — required `Shape` enum name with `manual_add` support.
- `x`, `y` — required sheet pixel coordinates of the symbol center.
- `system_id` — optional; inferred from `staff_id` or location.
- `staff_id` — optional; required when the point lies in multiple staves.
- `snap` — default `true`; apply shape-specific snapping.
- `link` — default `true`; include `searchLinks` results from `preAdd`.

Result:

```json
{
  "symbol": { "inter_id": 88, "..." : "SymbolSummary" },
  "links": [ { "source_id": 88, "target_id": 90, "type": "StemHeadRelation" } ]
}
```

Invalid shape or unsupported manual addition returns `INVALID_ARGUMENT` or
`UNSUPPORTED`.

### `remove_symbols`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "inter_ids": [88],
  "cascade": true,
  "rebuild_glyphs": false
}
```

- `cascade` — default `true`; use `RemovalScenario` to remove dependent
  ensembles/members.
- `rebuild_glyphs` — default `false`; v1 does not rebuild pixels. Passing `true`
  returns `UNSUPPORTED`.

Result:

```json
{
  "removed": [88],
  "not_found": [],
  "warnings": [
    {
      "code": "GLYPH_LEFT_AS_FREE",
      "message": "Removed inter #88; its glyph remains as a free glyph."
    }
  ]
}
```

### `move_symbol`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "inter_id": 88,
  "x": 340,
  "y": 405,
  "snap": true,
  "relink": true
}
```

- `snap` — default `true`; apply class-specific snapping.
- `relink` — default `true`; recompute links/unlinks for the new position.

Result:

```json
{
  "symbol": { "inter_id": 88, "..." : "SymbolSummary" },
  "links": []
}
```

### `update_symbol`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "inter_id": 88,
  "changes": {
    "shape": "NOTEHEAD_VOID",
    "text": "Andante",
    "numerator": 6,
    "denominator": 8,
    "value": 3,
    "voice_id": 2,
    "pitch": 60,
    "role": "ChordName",
    "syllable": "BEGIN",
    "staff_id": 3
  },
  "relink": true
}
```

`changes` is required. Supported keys by class in v1:

| Key | Target classes |
| :--- | :--- |
| `shape` | classes where `renameShapeAs` is valid; validated by the server |
| `staff_id` | any inter with a staff |
| `pitch` | `AbstractPitchedInter` |
| `voice_id` | `AbstractChordInter` |
| `text` | `WordInter`, `LyricItemInter`, `MetronomeInter` |
| `syllable` | `LyricItemInter` |
| `role` | `SentenceInter` |
| `numerator`, `denominator` | `TimeCustomInter` |
| `value` | `AbstractNumberInter` |

Unsupported keys for the target class return `UNSUPPORTED`.

Result: updated `SymbolSummary`.

### `link_symbols`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "source_id": 88,
  "target_id": 90,
  "relation_type": "StemHeadRelation"
}
```

Both inters must belong to the same system SIG in v1. Cross-system connections
return `UNSUPPORTED`.

Result:

```json
{
  "relation": {
    "relation_id": "r:1:88:StemHeadRelation:90",
    "source_id": 88,
    "target_id": 90,
    "type": "StemHeadRelation"
  }
}
```

Duplicate relations return `CONFLICT`.

### `unlink_symbols`

Input, form A:

```json
{
  "book_id": "...",
  "sheet": 1,
  "relation_id": "r:1:88:StemHeadRelation:90"
}
```

Input, form B:

```json
{
  "book_id": "...",
  "sheet": 1,
  "source_id": 88,
  "target_id": 90,
  "relation_type": "StemHeadRelation",
  "all": false
}
```

`all` default `false`; when true, remove every matching relation.

Result:

```json
{
  "removed": [
    {
      "source_id": 88,
      "target_id": 90,
      "type": "StemHeadRelation"
    }
  ]
}
```

### `toggle_tie`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "inter_id": 3129
}
```

Toggles the tie flag of a `SlurInter`.

Result:

```json
{
  "symbol": {
    "inter_id": 3129,
    "class": "SlurInter",
    "attributes": {
      "tie": true
    }
  }
}
```

### `merge_chords`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "chord_ids": [3011, 3012],
  "with_stem": true
}
```

Merges head chords belonging to the same SIG. `with_stem` defaults to `true`.

Result: updated merged `SymbolSummary`.

### `split_chord`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "inter_id": 3643
}
```

Splits one `HeadChordInter` into two chords.

Result:

```json
{
  "chords": [
    {"inter_id": 3645, "class": "HeadChordInter"},
    {"inter_id": 3646, "class": "HeadChordInter"}
  ]
}
```

### `merge_systems`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "system_id": 1
}
```

Merges the given system with the system immediately below it.

Result:

```json
{
  "system_id": 1,
  "ok": true
}
```

### `undo_edit`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "count": 1
}
```

`count` default 1, range 1–100.

Result:

```json
{
  "undone": 1,
  "history": {
    "position": 2,
    "can_undo": true,
    "can_redo": true
  }
}
```

### `redo_edit`

Same input and result shape as `undo_edit`, with `redone` instead of `undone`.

### `get_edit_history`

Input:

```json
{
  "book_id": "...",
  "sheet": 1
}
```

Result:

```json
{
  "position": 3,
  "can_undo": true,
  "can_redo": false,
  "entries": [
    {
      "index": 0,
      "op": "add",
      "summary": "add NOTEHEAD_BLACK #88",
      "inter_ids": [88],
      "at": "2026-10-28T12:10:00Z"
    }
  ]
}
```

### `reprocess_rhythm`

Input:

```json
{
  "book_id": "...",
  "sheet": 1,
  "stack_id": 4,
  "page_id": null
}
```

At most one of `stack_id` or `page_id` is given. If neither is given, the first
page of the sheet is reprocessed.

Result:

```json
{
  "stack_id": 4,
  "page_id": null,
  "ok": true
}
```

## 12. Validation rules

### Shapes

- Shape names are exact `Shape` enum names, case-sensitive.
- `add_symbol` accepts only shapes with `manual_add: true`.
- `update_symbol.shape` accepts only shapes valid for the target class.
- Unknown shapes return `INVALID_ARGUMENT`.

### Relations

- Relation types are exact simple class names under
  `org.audiveris.omr.sig.relation`, case-sensitive.
- Only relation classes with a no-argument constructor are accepted.
- `source_id` and `target_id` must exist, be non-removed, and share a SIG in v1.
- Duplicate source/target/type triples return `CONFLICT`.

### Symbols

- `inter_id` must exist on the specified sheet and be non-removed.
- Editing a removed inter returns `NOT_FOUND`.
- A `bbox` filter must have positive `width` and `height`.
- `limit` and `offset` must be non-negative; pagination is stable within one
  call but not across edits.

### Paths and artifacts

- Paths are checked before any file access.
- HTTP output outside allowed roots returns `PERMISSION_DENIED`.
- Existing files are overwritten only for explicit `output_path` values.
- `.omr` saves use a backup only when the tool documents it; v1 does not add a
  backup option.

### Jobs

- A book can have at most one running processing/edit job at a time.
- Starting a second job on the same book returns `JOB_BUSY`.
- Read-only inspection is allowed while a job runs only if the book is not being
  mutated by that job; otherwise it waits for the book lock.
- Jobs are retained until `--job-retention` expires (default 1 hour) or the
  server shuts down.

## 13. Examples

### Transcribe and export

```json
// 1. open_input
{"path":"data/examples/cucaracha.png","save_book":true}
=> {"ok":true,"data":{"book":{"book_id":"b1","radix":"cucaracha","sheet_count":1}},"warnings":[]}

// 2. transcribe
{"book_id":"b1","save":true,"swap":true}
=> {"ok":true,"data":{"job_id":"j1"},"warnings":[]}

// 3. get_job
{"job_id":"j1"}
=> {"ok":true,"data":{"job_id":"j1","state":"SUCCEEDED","progress":{"percent":100},"result":{"scores":1,"pages":1}},"warnings":[]}

// 4. export_musicxml
{"book_id":"b1","output_path":"output/cucaracha","format":"mxl"}
=> {"ok":true,"data":{"job_id":"j2"},"warnings":[]}

// 5. get_job
{"job_id":"j2"}
=> {"ok":true,"data":{"job_id":"j2","state":"SUCCEEDED","result":{"outputs":[{"kind":"musicxml","path":"/abs/output/cucaracha.mxl","format":"mxl","score_index":0}]}},"warnings":[]}
```

### Inspect and edit

```json
// list symbols
{"book_id":"b1","sheet":1,"system_id":1,"shape":"NOTEHEAD_BLACK","include_relations":true}
=> {"ok":true,"data":{"total":1,"symbols":[{"inter_id":77,"shape":"NOTEHEAD_BLACK","bounds":[300,400,16,12],"relations":[{"source_id":77,"target_id":90,"type":"StemHeadRelation"}]}]},"warnings":[]}

// move it
{"book_id":"b1","sheet":1,"inter_id":77,"x":320,"y":410}
=> {"ok":true,"data":{"symbol":{"inter_id":77,"bounds":[312,404,16,12]},"links":[]},"warnings":[]}

// add a symbol
{"book_id":"b1","sheet":1,"shape":"NOTEHEAD_BLACK","x":340,"y":410,"staff_id":3}
=> {"ok":true,"data":{"symbol":{"inter_id":88,"shape":"NOTEHEAD_BLACK"},"links":[{"source_id":88,"target_id":90,"type":"StemHeadRelation"}]},"warnings":[]}

// link an additional relation
{"book_id":"b1","sheet":1,"source_id":88,"target_id":91,"relation_type":"SlurHeadRelation"}
=> {"ok":true,"data":{"relation":{"source_id":88,"target_id":91,"type":"SlurHeadRelation"}},"warnings":[]}

// undo twice
{"book_id":"b1","sheet":1,"count":2}
=> {"ok":true,"data":{"undone":2,"history":{"position":0,"can_undo":false,"can_redo":true}},"warnings":[]}

// save
{"book_id":"b1"}
=> {"ok":true,"data":{"book_path":"/abs/output/cucaracha.omr","saved":true},"warnings":[]}
```

### Error

```json
{"book_id":"b1","sheet":1,"inter_id":999}
=> {
  "ok": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Inter 999 not found on sheet 1",
    "details": {"book_id":"b1","sheet":1,"inter_id":999}
  }
}
```

## 14. Versioning

- `interface_version` follows `major.minor`.
- Additive tools, fields, enum values, and warning codes are minor changes.
- Removing or renaming a tool, field, or error code is a major change.
- Clients must ignore unknown response fields and warning codes.
- Clients must not rely on `relation_id` or log text across server restarts.

## 15. Reserved for later

The following are intentionally out of v1:

- MCP resources and prompts
- streaming partial MusicXML
- cross-system symbol linking (except future slur connection)
- glyph pixel rebuilding after removal
- split/merge chords, merge systems, toggle tie
- symbol-level batch transactions with explicit commit/rollback
- remote authentication beyond a bearer token
