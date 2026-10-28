# Audiveris MCP server

Native Java MCP server for the Audiveris OMR engine. It runs the engine
in-process, without starting the GUI.

The tool contract is in [`INTERFACE.md`](INTERFACE.md). The architecture and
roadmap are in [`../PLAN.md`](../PLAN.md).

## Status

Implemented:

- STDIO transport
- headless Audiveris bootstrap
- diagnostics: `version`, `list_steps`, `list_ocr_languages`, `list_shapes`,
  `list_relation_types`
- book sessions: `open_input`, `open_book`, `close_book`, `list_books`,
  `get_book`, `get_sheets`, `get_sheet`, `save_book`
- sheet selection and validity
- `set_parameters` for `ocr_languages`
- asynchronous jobs: `get_job`, `list_jobs`, `cancel_job`, `get_log`
- processing and export: `run_step`, `transcribe`, `export_musicxml`,
  `print_book`

`print_book` depends on Audiveris PDF rendering and can report `IO_ERROR` when
the upstream renderer fails on a particular score.

Not implemented yet:

- score and symbol inspection
- symbol editing
- Streamable HTTP transport

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

Or during development:

```sh
./gradlew :mcp:run --args="--transport stdio --workspace /path/to/workspace"
```

The server uses `--workspace` for relative input/output paths. Open inputs and
`.omr` files are stored under `<workspace>/books`.

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

## Tests

```sh
./gradlew :mcp:test
```
