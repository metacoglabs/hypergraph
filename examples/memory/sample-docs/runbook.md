# Runbook

## Backups
Stop writes, run CHECKPOINT, then copy the data volume. A copy taken while the server runs may need recovery on open.

## Rebuilding the text index
After an upgrade that changes tokenisation, run REBUILD TEXT INDEX as the superuser. Writes may continue while it runs.
