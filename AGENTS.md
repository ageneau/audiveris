# AGENTS.md

Guidance for agents and contributors working in this repository.

## What this is

Audiveris — open-source Optical Music Recognition (OMR) application that transcribes
score images into MusicXML and editable `.omr` project data.

## Layout

- `app/` — Java OMR engine and editor
- `docs/` — user handbook sources
- `packaging/` — OS-specific installers
- `flatpak/` — Linux/Flatpak packaging
- `schemas/` — `.omr` XML schema documentation
- `data/examples/` — sample scores for manual and automated testing

See [`project-structure.md`](project-structure.md) for the authoritative layout.

## Commands

```sh
./gradlew build -x test     # build everything
./gradlew test              # run unit tests
./gradlew :app:run          # run the GUI
./gradlew :app:run -PcmdLineArgs="-batch,-transcribe,-export,../data/examples/cucaracha.png"
./gradlew :app:installDist  # create a runnable app distribution
```

Requirements: JDK 25 (see `gradle.properties`) and Tesseract language data
(`TESSDATA_PREFIX`) for OCR.

## Conventions

- Work from the `development` branch; keep changes focused and match surrounding style.
- Use `git grep`/`rg` to search tracked sources; do not edit `build/` outputs.
- Update `project-structure.md` when adding a Gradle sub-project.
- Preserve the AGPL license headers on new Java sources.
