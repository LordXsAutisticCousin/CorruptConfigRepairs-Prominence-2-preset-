# CorruptConfigAnnihilator

A Fabric 1.20.1 `preLaunch` utility that provisions default configurations, applies modpack configuration updates, and repairs corrupted or invalid configuration files prior to game initialization.

---

## Key Capabilities

| Scenario | Standard Modpack Launchers | CorruptConfigAnnihilator |
| :--- | :--- | :--- |
| Fresh install, missing configuration | Copies template | Copies template |
| User-modified configuration | Retains unmodified | Retains unmodified |
| 0-byte, truncated, or invalid file from crash | Skips existing file (causes crash loop) | Creates backup and restores template |
| Updated default configuration in modpack release | Does not update existing installs | Updates files that match previous default hash |
| Modpack adds keybindings; existing `options.txt` present | New keybindings omitted | Appends missing keys; preserves existing lines |
| Enforced files (server listings, mandatory rules) | Not supported | Enforced via `overrides/` |
| Non-standard configuration format | Not supported | Configured via `ignore.txt` exclusion |
| Interrupted write during boot | Leaves partial file | Atomic filesystem rename ensures file integrity |
| Non-configuration files (`.gitkeep`, `.DS_Store`) | Often copied | Automatically excluded |
| Directory path specified in template location | Skipped without notice | Logged (`skip-dir`) and skipped safely |
| Unmanaged configuration corrupted (no template) | Not handled | Quarantined to backup directory for regeneration |

---

## Execution Logic

During the Fabric `preLaunch` phase, each configuration candidate is resolved according to the following order of precedence:

```text
overrides/<path>
  └── Live file made byte-identical; prior version backed up   (override-apply)

defaults/<path>
  ├── Live file missing                                        → Copy template              (default-apply)
  ├── Live file corrupted                                      → Backup and restore template (restore)
  ├── Live file unmodified from prior applied template,
  │   template modified in modpack update                      → Backup and apply update    (default-update)
  ├── Live /options.txt present, template contains new keys    → Append missing keys        (options-merge)
  └── Live file valid and user-modified                        → Preserved without changes

Any other config/**.{json,json5,jsonc,toml,snbt,properties,cfg}
  └── Corrupted, no template available                         → Quarantined to backups     (quarantine)

Matches in ignore.txt
  └── Existing files on disk                                   → Excluded from validation and modification
```

### Safety & Storage Principles
- **Modification Invariant:** Configuration files whose contents differ from the recorded applied template hash are never overwritten. Hashes of applied templates are maintained in `config/corruptconfigannihilator/applied.txt`.
- **Automatic Backups:** Replaced or quarantined files are moved to `corruptconfigannihilator_backups/<yyyyMMdd_HHmmss>/`. The 20 most recent snapshots are retained.
- **Atomic Operations:** File writes are executed via temporary files followed by atomic filesystem moves (`StandardCopyOption.ATOMIC_MOVE`), preventing partial file writes.
- **Logging:** All actions are recorded in `logs/corruptconfigannihilator.log` and mirrored to the standard game log.

---

## Configuration Directories

### 1. `config/corruptconfigannihilator/defaults/` — Default Configurations
Contains default configurations for mods and root files. Standard root files (`options.txt`, `options*.txt`, `servers.dat`, `hotbar.nbt`, `resourcepacks/`, `shaderpacks/`) are automatically routed to the root game directory. Mod configurations are placed directly in the directory or inside a `config/` subfolder.

```text
config/corruptconfigannihilator/defaults/
├── options.txt                     → /options.txt
├── servers.dat                     → /servers.dat
├── puffish_skills/skills.json      → /config/puffish_skills/skills.json
└── general.toml                    → /config/general.toml
```

#### `options.txt` Merging Behavior
Because the game client rewrites `options.txt` upon session termination, standard file hash matching does not apply. When `options.txt` is evaluated against an updated template, missing `key:value` pairs defined in the template are appended (`options-merge`), while all existing user settings and keybindings remain unmodified.

### 2. `config/corruptconfigannihilator/overrides/` — Enforced Configurations
Paths are resolved relative to the game directory. Target files are overwritten to match the override template on every launch, with prior contents backed up before replacement. This directory is intended for mandatory server lists, locked client parameters, and forced assets. Files modified at runtime by the client should not be placed here.

### 3. `config/corruptconfigannihilator/ignore.txt` — Exclusion Rules
Contains gitignore-style glob patterns (one per line, case-insensitive, `#` for comments) resolved relative to the game directory. Existing files matching an active rule are excluded from syntax verification, updates, and overrides. Missing files matching an ignore rule will still be seeded on initial install if a template exists.

```text
# Exclude entire mod configuration folder
config/somemod/

# Exclude specific file
config/other-mod/custom.json

# Exclude files matching pattern across directories
config/**/cache.json
*.sol.json
```

---

## Distribution & Modpack Integration

CorruptConfigAnnihilator supports two distribution workflows:
1. **Self-Contained Mod JAR (Embedded Templates):** Configuration templates in `config/` are packaged directly into the compiled `.jar` during build. Modpacks only require the resulting `.jar` inside `mods/`.
2. **Standard Modpack Structure (External Templates):** A prebuilt `.jar` is placed in `mods/`, and configuration templates are distributed in the modpack's `config/` directory.

---

### Method 1: Building a Self-Contained Mod JAR

When built from source, Gradle packages the repository's `config/` directory into `default_configs/` within the `.jar`. At runtime, the mod loads and applies these embedded templates automatically.

#### Requirements
- **Java Development Kit (JDK):** JDK 17 or JDK 21.
  - Verification: `java -version`
- **Build Tool / IDE:** IntelliJ IDEA (Community or Ultimate) or standard terminal with Gradle wrapper.
- **Network Access:** Required during initial Gradle execution to retrieve dependencies, Yarn mappings, and the Fabric Loom plugin.

---

#### Project Setup & Build Procedure

##### 1. Repository Setup
- Clone the repository:
  ```bash
  git clone https://github.com/LordXsAutisticCousin/CorruptConfigRepairs.git
  ```
- Or extract the downloaded source archive.

##### 2. Project Import & IDE Configuration (IntelliJ IDEA)
1. Open IntelliJ IDEA and select **Open** on the project root folder.
2. Open as a **Gradle project**.
3. Set the Gradle JVM:
   - Navigate to **Settings ➔ Build, Execution, Deployment ➔ Build Tools ➔ Gradle**.
   - Set **Gradle JVM** to **JDK 17** or **JDK 21**.
4. Set the Project SDK:
   - Navigate to **File ➔ Project Structure ➔ Project**.
   - Set **SDK** to **JDK 17** or **JDK 21**.

##### 3. Configuration Template Placement
Place configuration files in the root `config/` directory:
- Direct structure: `config/somemod/config.json`, `config/options.txt`
- Structured hierarchy: `config/corruptconfigannihilator/defaults/`, `config/corruptconfigannihilator/overrides/`, `config/corruptconfigannihilator/ignore.txt`

##### 4. Compilation
- **Via IntelliJ IDEA:** Open the **Gradle** tool window, navigate to `Tasks ➔ build`, and run **`build`**.
- **Via Terminal:**
  - **Windows:**
    ```powershell
    .\gradlew.bat build
    ```
  - **macOS / Linux:**
    ```bash
    chmod +x gradlew
    ./gradlew build
    ```

##### 5. Deployment
The compiled mod artifact is located at:
```text
build/libs/corruptconfigannihilator-1.0.0.jar
```
Copy `corruptconfigannihilator-1.0.0.jar` into the modpack `mods/` directory.

---

#### Troubleshooting

- **`Unsupported class file major version`:**
  - Set **Gradle JVM** under **Settings ➔ Build Tools ➔ Gradle** to JDK 17 or JDK 21.
- **`Permission denied` (POSIX systems):**
  - Execute `chmod +x gradlew` in the project root.
- **Decompiled Source Resolution:**
  - Execute `.\gradlew.bat genSources` to generate mappings and decompiled sources for IDE navigation.
- **Dependency Cache Refresh:**
  - Execute `.\gradlew.bat --refresh-dependencies build`.

---

### Method 2: Distributing External Configuration Templates

When using a prebuilt release `.jar`, distribute the mod `.jar` and configuration directories together within the modpack archive:

```text
MyModpack/
├── mods/
│   └── corruptconfigannihilator-1.0.0.jar
└── config/
    └── corruptconfigannihilator/
        ├── defaults/                  → Default templates (applied on missing/corrupt)
        │   ├── options.txt
        │   └── somemod/config.json
        ├── overrides/                 → Enforced templates (synchronized every launch)
        │   └── servers.dat
        └── ignore.txt                 → Exclusion patterns
```

*(Note: Disk-based overrides and local configurations maintain precedence over embedded `.jar` defaults).*

---

## Modpack Update Behavior

1. Modify configuration templates in `defaults/`.
2. Package and distribute the updated modpack.
3. Upon client launch:
   - Unmodified configurations matching the previous template hash are updated to the new default (`default-update`), with prior versions archived in backups.
   - User-customized configurations are preserved without modification.

### Scope of Automated Updates
Automated updates apply strictly to files whose content matches the previously deployed template hash. Files modified by third-party mods during initial load or rewritten by the game client (such as `options.txt`) are treated as user-modified to prevent unintentional overwrites. Consequently, automated template updates apply primarily to static data assets, KubeJS scripts, quest definitions, datapack JSON files, and read-only configurations. Runtime-modified configurations requiring mandatory synchronization should be placed in `overrides/`.

---

## Syntax Validation Rules

| Format | Validation Method |
| :--- | :--- |
| `.json` | Validated via Gson (lenient) and Jackson JSON5 parsers (supports comments, trailing commas); root must resolve to an object or array. |
| `.json5`, `.jsonc` | Validated via JSON5 parser (comments, unquoted keys, trailing commas); root must resolve to an object or array. |
| `.toml` | Validated via NightConfig TOML parser with lenient bare key handling (excluding `balm-*.toml`). |
| `.properties` | Validated via `java.util.Properties` loader. |
| `options.txt`, `optionsof.txt`, `optionsshaders.txt` | Must be non-empty; all non-comment lines must contain `:` or `=`. |
| General text (`.cfg`, `.snbt`, `.txt`, `.conf`, `.yml`, etc.) | Checked for NUL byte corruption. |
| Binary (`.dat`, `.nbt`, `.zip`, `.jar`, `.png`, etc.) | Verified as non-empty (size > 0 bytes). |
| World data (`.mca`) & extensionless files | Opaque binary format; excluded from corruption verification. |
| Files > 16 MB | Opaque; excluded from memory-mapped preLaunch verification. |

An unparseable live file that matches its source template is treated as an unmanaged format and left unaltered.

---

## Log Reference (`logs/corruptconfigannihilator.log`)

| Action | Description |
| :--- | :--- |
| `default-apply <path>` | Missing file created from template. |
| `default-update <path>` | Unmodified file updated to match new modpack default (prior version backed up). |
| `options-merge <path> added=N` | N missing `key:value` pairs appended to `options.txt` (prior version backed up). |
| `override-apply <path>` | File synchronized with `overrides/` template (prior version backed up). |
| `repeat-corrupt <path> streak=N` | File flagged as corrupt across consecutive launches; candidate for `ignore.txt`. |
| `restore <path>` | Corrupted file archived to backups and restored from template. |
| `quarantine <path>` | Corrupted unmanaged file moved to backups for engine regeneration. |
| `corrupt-template <path>` | Template file failed syntax validation. |
| `skip-dir <path>` | Template path resolves to a directory; skipped. |
| `*-fail <path> <error>` | I/O operation failure; error details logged to game log. |
| `scan-ok scanned=N` | Scan completed; no remediation required. |

---

## Additional Information

- The orphan configuration scan skips: `config/corruptconfigannihilator/`, `jei/`, `rei/`, `emi/`, `spark/`, and all patterns in `ignore.txt`.
- Deletion of `applied.txt` causes healthy template-matching files to be re-indexed upon next launch.
