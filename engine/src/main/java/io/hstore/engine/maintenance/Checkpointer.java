// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.CatalogImage;
import io.hstore.engine.catalog.CatalogStore;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.txn.TransactionManager;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.util.List;

public final class Checkpointer {

    public record Result(long generation, long lsn, int walSegments) {
    }

    private final TransactionManager transactions;
    private final PageStore pages;
    private final WriteAheadLog wal;
    private final ChangeFeed feed;
    private final CatalogStore catalog;
    private final int historyLimit;
    private volatile long lastLsn;

    public Checkpointer(TransactionManager transactions, PageStore pages, WriteAheadLog wal, ChangeFeed feed,
                        CatalogStore catalog, int historyLimit, long recoveredLsn) {
        this.transactions = transactions;
        this.pages = pages;
        this.wal = wal;
        this.feed = feed;
        this.catalog = catalog;
        this.historyLimit = historyLimit;
        this.lastLsn = recoveredLsn;
    }

    public long walSinceCheckpoint() {
        return wal.end() - lastLsn;
    }

    public Result checkpoint() {
        return transactions.exclusive(() -> {
            pages.sync();
            feed.sync();
            Generation current = transactions.current();
            long lsn = wal.end();
            List<Generation> history = transactions.history().stream()
                    .filter(generation -> generation.id() != current.id())
                    .sorted((a, b) -> Long.compare(b.id(), a.id()))
                    .limit(historyLimit)
                    .toList();
            catalog.publish(new CatalogImage(current, history, pages.segments(), lsn, feed.size()));
            wal.append(new WalRecord.Checkpoint(0, current.id(), lsn));
            wal.sync();
            wal.truncateBefore(lsn);
            lastLsn = lsn;
            return new Result(current.id(), lsn, wal.segmentCount());
        });
    }
}
