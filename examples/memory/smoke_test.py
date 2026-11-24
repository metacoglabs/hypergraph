# SPDX-FileCopyrightText: MetaCognition India Pvt Limited
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import json
import os
import sys
import time

from hstore_memory import HStore, HStoreError, Memory

url = os.environ["HSTORE_URL"]
deadline = time.time() + 120
while True:
    try:
        HStore(url).info()
        break
    except (HStoreError, OSError):
        if time.time() > deadline:
            sys.exit("server did not answer in 120 seconds")
        time.sleep(1)

memory = Memory(HStore(url, os.environ["HSTORE_USER"], os.environ["HSTORE_PASSWORD"]), kind="Doc")
failures = []


def check(label, ok, detail=""):
    print(("ok   " if ok else "FAIL ") + label + ("" if ok else f": {detail}"))
    if not ok:
        failures.append(label)


stage = sys.argv[1]
if stage == "load":
    stored = memory.ingest(os.path.join(os.path.dirname(__file__), "sample-docs"))
    check("ingest stores three documents", len(stored) == 3, stored)
    check("sections split at headings", sum(int(revision["sections"]) for _, revision in stored) == 10, stored)
    hits = memory.search("purge erases stored text")
    check("search finds the purge section first", bool(hits) and "Consequences" in str(hits[0].get("path")), hits[:2])
    memory.remember("runbook.md", "# Runbook\n\n## Backups\nRun CHECKPOINT, then snapshot the volume with the server stopped.\n")
    check("a new revision replaces the latest text", not memory.search("recovery on open"), memory.search("recovery on open"))
    check("history still finds the old revision", bool(memory.search("recovery on open", history=True)))
    answer = memory.retrieve("how do I back up the database?", top=3)
    check("retrieve answers from the runbook", bool(answer) and any("Backups" in str(row.get("heading")) for row in answer), answer)
    before = [[hit["section"], hit["score"]] for hit in memory.search("search sections revisions", top=20)]
    message = memory.rebuild_text_index()
    check("rebuild reports the sections it indexed", message.startswith("rebuilt the text index from"), message)
    after = [[hit["section"], hit["score"]] for hit in memory.search("search sections revisions", top=20)]
    check("rebuild keeps the ranking", before == after, (before, after))
    with open(os.environ["SMOKE_STATE"], "w") as state:
        json.dump(after, state)
elif stage == "after-restart":
    with open(os.environ["SMOKE_STATE"]) as state:
        expected = json.load(state)
    after = [[hit["section"], hit["score"]] for hit in memory.search("search sections revisions", top=20)]
    check("ranking survives a restart", after == expected, (after, expected))
    check("purge reports what it removed", "purged" in memory.forget("team/onboarding.md"))
    check("purged text is gone from search", not memory.search("CREATE USER tenants"))
    try:
        memory.latest("team/onboarding.md")
        check("purged document is gone", False, "still readable")
    except HStoreError as failure:
        check("purged document is gone", "no Doc named" in str(failure), failure)
print("FAILURES", len(failures))
sys.exit(1 if failures else 0)
