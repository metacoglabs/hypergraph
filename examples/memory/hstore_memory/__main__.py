# SPDX-FileCopyrightText: MetaCognition India Pvt Limited
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import argparse
import json
import sys

from .client import HStoreError
from .memory import Memory


def show(rows, fields):
    for row in rows:
        print("  ".join(f"{field}={row.get(field)}" for field in fields if field in row))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m hstore_memory", description="Store and search Markdown memory in HStore.")
    parser.add_argument("--type", default="Doc", help="node type that holds the documents (default Doc)")
    commands = parser.add_subparsers(dest="command", required=True)
    ingest = commands.add_parser("ingest", help="store every Markdown file under a directory as a new revision")
    ingest.add_argument("directory")
    ingest.add_argument("--no-embed", action="store_true", help="skip section vectors")
    remember = commands.add_parser("remember", help="store a new revision of one document")
    remember.add_argument("key")
    remember.add_argument("file", help="Markdown file, or - for standard input")
    search = commands.add_parser("search", help="BM25 search over the latest revisions")
    search.add_argument("query")
    search.add_argument("--top", type=int, default=10)
    search.add_argument("--history", action="store_true")
    retrieve = commands.add_parser("retrieve", help="fused lexical, vector and graph retrieval")
    retrieve.add_argument("question")
    retrieve.add_argument("--top", type=int, default=5)
    forget = commands.add_parser("forget", help="purge a document and erase its stored text")
    forget.add_argument("key")
    commands.add_parser("rebuild", help="rebuild the text index (default-tenant admin only)")
    hql = commands.add_parser("hql", help="run an HQL script and print the JSON frames")
    hql.add_argument("script")
    args = parser.parse_args(argv)

    memory = Memory(kind=args.type)
    try:
        if args.command == "ingest":
            for key, revision in memory.ingest(args.directory, embed=not args.no_embed):
                print(f"{key}: revision {revision.get('number')} with {revision.get('sections')} sections")
        elif args.command == "remember":
            text = sys.stdin.read() if args.file == "-" else open(args.file, encoding="utf-8").read()
            print(json.dumps(memory.remember(args.key, text), default=str))
        elif args.command == "search":
            show(memory.search(args.query, args.top, args.history), ["score", "document", "path", "terms"])
        elif args.command == "retrieve":
            for row in memory.retrieve(args.question, args.top):
                print(f"[{row.get('score'):.4f}] {row.get('heading')}\n{(row.get('text') or '').strip()}\n")
        elif args.command == "forget":
            print(memory.forget(args.key))
        elif args.command == "rebuild":
            print(memory.rebuild_text_index())
        elif args.command == "hql":
            print(json.dumps(memory.client.execute(args.script), indent=2, default=str))
    except HStoreError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
