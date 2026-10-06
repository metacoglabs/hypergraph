// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

record JvmUsage(long collections, long pauseMillis, long allocated) {

    static JvmUsage now() {
        long collections = 0;
        long pauseMillis = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, collector.getCollectionCount());
            pauseMillis += Math.max(0, collector.getCollectionTime());
        }
        long allocated = ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes();
        return new JvmUsage(collections, pauseMillis, allocated);
    }

    JvmUsage since(JvmUsage start) {
        return new JvmUsage(collections - start.collections, pauseMillis - start.pauseMillis, allocated - start.allocated);
    }

    static long retainedHeap() {
        System.gc();
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
