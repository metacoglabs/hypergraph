# SPDX-FileCopyrightText: MetaCognition India Pvt Limited
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import os
import re
from pathlib import Path

from .client import HStore, HStoreError, quote

IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def connect():
    return HStore(os.environ.get("HSTORE_URL", "http://127.0.0.1:7480"),
                  os.environ.get("HSTORE_USER", "admin"), os.environ.get("HSTORE_PASSWORD"))


class Memory:
    def __init__(self, client=None, kind="Doc"):
        if not IDENTIFIER.match(kind):
            raise ValueError(f"{kind!r} is not a valid type name")
        self.client = client or connect()
        self.kind = kind
        self._ready = False

    def ensure_type(self):
        if self._ready:
            return
        names = {row["name"] for row in self.client.rows("SHOW TYPES;")}
        if self.kind not in names:
            try:
                self.client.execute(f"CREATE NODE TYPE {self.kind};")
            except HStoreError as failure:
                if "already exists" not in str(failure):
                    raise
        self._ready = True

    def ref(self, key):
        return f"@{self.kind}:{quote(key)}"

    def remember(self, key, markdown, embed=True, note=None, valid_from=None):
        self.ensure_type()
        options = ""
        if valid_from is not None:
            options += f" VALID FROM {quote(valid_from)}"
        if note:
            options += f" NOTE {quote(note[:200])}"
        if embed:
            options += " EMBED"
        try:
            return self.client.rows(f"REVISE {self.ref(key)} MARKDOWN {quote(markdown)}{options};")[0]
        except HStoreError as failure:
            if f"no {self.kind} named" not in str(failure):
                raise
        return self.client.rows(f"INSERT NODE {self.kind} {quote(key)} AS $n;\nREVISE $n MARKDOWN {quote(markdown)}{options};")[0]

    def ingest(self, directory, pattern="**/*.md", embed=True):
        root = Path(directory)
        stored = []
        for path in sorted(root.glob(pattern)):
            if path.is_file():
                key = path.relative_to(root).as_posix()
                stored.append((key, self.remember(key, path.read_text(encoding="utf-8"), embed=embed)))
        return stored

    def search(self, query, top=10, history=False):
        self.ensure_type()
        return self.client.rows(f"SEARCH TEXT {quote(query)} IN {self.kind}{' HISTORY' if history else ''} TOP {int(top)};")

    def retrieve(self, question, top=10, anchors=()):
        self.ensure_type()
        anchor = f" ANCHORS ({', '.join(self.ref(key) for key in anchors)})" if anchors else ""
        return self.client.rows(f"RETRIEVE {quote(question)} IN ({self.kind}){anchor} TOP {int(top)};")

    def revisions(self, key):
        return self.client.rows(f"REVISIONS OF {self.ref(key)};")

    def latest(self, key):
        rows = self.client.rows(f"REVISION OF {self.ref(key)};")
        return rows[0] if rows else None

    def forget(self, key):
        return self.client.message(f"PURGE {self.ref(key)};")

    def rebuild_text_index(self):
        return self.client.message("REBUILD TEXT INDEX;")
