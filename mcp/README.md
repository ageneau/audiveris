# Audiveris MCP server

Native Java MCP server for the Audiveris OMR engine. It runs the engine
in-process, without starting the GUI.

The tool contract is in [`INTERFACE.md`](INTERFACE.md). The architecture and
roadmap are in [`../PLAN.md`](../PLAN.md).

## Status

Implemented:

- STDIO and Streamable HTTP transports
- headless Audiveris bootstrap
- diagnostics: `version`, `list_steps`, `list_ocr_languages`, `list_shapes`,
  `list_relation_types`
- book sessions: `open_input`, `open_book`, `close_book`, `list_books`,
  `get_book`, `get_sheets`, `get_sheet`, `save_book`
- sheet selection and validity
- `set_parameters` for `ocr_languages`, interline, beam, input quality, fonts and processing switches
- asynchronous jobs: `get_job`, `list_jobs`, `cancel_job`, `get_log`
- processing and export: `run_step`, `transcribe`, `export_musicxml`,
  `print_book`
- inspection: `get_score`, `list_symbols`, `get_symbol`
- constants: `get_constants`, `set_constant`, `reset_constant`
- editing: `add_symbol`, `remove_symbols`, `move_symbol`, `link_symbols`,
  `unlink_symbols`, `undo_edit`, `redo_edit`, `get_edit_history`

`print_book` depends on Audiveris PDF rendering and can report `IO_ERROR` when
the upstream renderer fails on a particular score.

Not implemented yet:

- advanced editing: `update_symbol`, `reprocess_rhythm`, split/merge chords,
  merge systems, toggle tie, glyph pixel rebuilding, cross-system links

## Requirements

- JDK 25 (see `gradle.properties`)
- Legacy-compatible Tesseract language data for OCR

Audiveris uses Tesseract's **legacy** engine. Debian/Ubuntu packages such as
`tesseract-ocr-eng` may provide LSTM-only data and fail with:

```text
Tesseract (legacy) engine requested, but components are not present
```

Use legacy-compatible files from the `tesseract-ocr/tessdata` repository:

```sh
mkdir -p ~/.config/AudiverisLtd/audiveris/tessdata
cd ~/.config/AudiverisLtd/audiveris/tessdata
for lang in eng fra spa; do
  curl -L -O "https://github.com/tesseract-ocr/tessdata/raw/4.1.0/${lang}.traineddata"
done
```

This is Audiveris' default OCR folder. Alternatively, put the files in another
directory and set `TESSDATA_PREFIX` to it.

The OCR language selection is a book/sheet parameter:

```json
{"book_id":"...","parameters":{"ocr_languages":"eng+fra+spa"}}
```

If only a JDK 21 is available, a development build can be attempted with:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :mcp:test -PtheMinJavaVersion=21
```

This is a local development workaround, not a supported project configuration.

## Build and run

```sh
./gradlew :mcp:installDist

mcp/build/install/mcp/bin/mcp \
  --transport stdio \
  --workspace /path/to/workspace
```

HTTP:

```sh
AUDIVERIS_MCP_TOKEN=secret \
mcp/build/install/mcp/bin/mcp \
  --transport http \
  --http-host 127.0.0.1 \
  --http-port 8765 \
  --http-path /mcp \
  --workspace /path/to/workspace
```

Use `--transport both` to serve STDIO and HTTP from one process.

Or during development:

```sh
./gradlew :mcp:run --args="--transport stdio --workspace /path/to/workspace"
```

The server uses `--workspace` for relative input/output paths. Open inputs and
`.omr` files are stored under `<workspace>/books`.

HTTP binds to `127.0.0.1` by default. A non-loopback bind requires a token,
provided with `--http-token` or `AUDIVERIS_MCP_TOKEN`; prefer the environment
variable to avoid exposing it in the process list.

## MCP client configuration

Example:

```json
{
  "mcpServers": {
    "audiveris": {
      "command": "/absolute/path/to/mcp/build/install/mcp/bin/mcp",
      "args": [
        "--transport",
        "stdio",
        "--workspace",
        "/absolute/path/to/workspace"
      ],
      "env": {
        "TESSDATA_PREFIX": "/usr/share/tesseract-ocr/5/tessdata"
      }
    }
  }
}
```

HTTP client example:

```json
{
  "mcpServers": {
    "audiveris": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer secret"
      }
    }
  }
}
```

## Tests

```sh
./gradlew :mcp:test
```
