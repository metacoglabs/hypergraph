# Storage decision

## Context
The notes service keeps every edit of a page. Search must find the current text first and still reach old versions
when someone asks for the history.

## Decision
Store pages as Markdown revisions in HStore. Each heading becomes a section, so search and retrieval return the part
of a page that answers the question rather than the whole page.

## Consequences
Old revisions stay readable. A purge removes a page and erases its stored text.
