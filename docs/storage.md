# Local storage and cache maintenance

Activity history, peer mirrors, offline outboxes and configuration commands live in `remote_sync_v1.db`. IDs, relationship generations, timestamps, sequence numbers, acknowledgement checkpoints and other indexed fields remain ordinary SQLite columns. Media originals, task proof and content-addressed assets retain their existing formats and ownership.

## Payload format

Remote database format 4 accepts legacy `TEXT` bodies and compressed `BLOB` bodies in the same tables. `StoredPayloads.read(cursor, column)` returns the original text for either representation. Store APIs such as `mirror()` and `mirrored()` perform this decoding before returning data. Every direct reader of a `body` column, including retained-history adapters, must use that boundary.

Version 1 blobs contain an eight-byte version/length envelope and a zlib stream with an immutable schema dictionary. Encoding is used only when it saves at least 32 bytes and one eighth of the original payload. Small, oversized, malformed-Unicode and incompressible strings retain their text representation. Decoding limits expanded blobs to 2 MiB and rejects unknown versions, truncation, bad integrity checks and trailing data. Compression provides no encryption or peer authentication.

Opening a legacy database adds maintenance progress metadata without rewriting history. New records use the codec automatically. An idle worker may call `RemoteStore.compactStoredPayloads(maximumRows)` with 1–256 rows per transaction. Each batch also limits decoded data to 1 MiB, with a single larger record processed alone. The method refuses the main thread. Its progress and record updates commit together, making interruption and retries safe. Background scheduling of old-row conversion is a separate integration decision.

Row conversion preserves keys, dates, original decoded text, outboxes and acknowledgement state. Existing database allocation can remain unchanged after conversion; SQLite can retain free space for subsequent writes. A freshly created database with compressed records can be smaller. Use SQLite checkpoint APIs for WAL maintenance. Removing WAL/SHM files manually can lose committed state.

## Cache lifecycle

Remote-editor files are derived from the saved configuration and retained asset store. A lease preserves them across activity recreation and child pages. Closing the last editor releases its derived tree; cleanup waits for any active import/reader. Startup reclaims editor trees left by a dead process when no live lease exists.

Known artwork, peer-import and task-import `.part` files have a 32 MiB idle budget and a seven-day maximum idle age. Editor leftovers also have a 32 MiB idle budget. Active imports and editor files are exempt until released, so active operations can temporarily exceed these idle budgets. Unrecognized files, persistent assets, offline queues, proof, diagnostics and update files are outside this cleanup. Directory symlinks are not traversed.

## Compatibility and recovery

Readers must decode payloads before parsing JSON or comparing immutable facts. In particular, retained local Activity adapters must decode `activity_source.body`; peer-history adapters should use the existing decoded mirror APIs. Backfill must retain original IDs/timestamps and its existing consent and relationship-generation filters.

Format 4 prevents older database helpers from silently interpreting blobs as plain JSON. Rollback requires a forward-version build that retains the codec reader. Its off-main-thread recovery tool can call `StoredPayloads.restorePlaintext(database)` inside a SQLite transaction, validate the decoded history/checkpoints, and then commit. The operation resets maintenance progress and is repeat-safe. Preserve the codec reader until no encoded rows remain; never downgrade or wipe the database as a recovery shortcut.

Native fixtures cover mixed rows, offline replay, bounded conversion, transaction failure, process interruption/restart, editor recreation/close and cache ownership. Disk and timing measurements from an emulator are fixture evidence; they do not attribute a physical phone's storage without a device-specific inventory.
