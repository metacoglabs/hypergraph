package io.hstore.engine;

import io.hstore.engine.page.PageId;

public sealed abstract class HStoreException extends RuntimeException {

    public enum Code { RETRYABLE_CONFLICT, RETRYABLE_IO, ABORTED_RESOURCE_LIMIT, INVALID_SCHEMA, CORRUPT_PAGE, CORRUPT_LOG }

    private HStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract Code code();

    public final boolean retryable() {
        return switch (code()) {
            case RETRYABLE_CONFLICT, RETRYABLE_IO -> true;
            case ABORTED_RESOURCE_LIMIT, INVALID_SCHEMA, CORRUPT_PAGE, CORRUPT_LOG -> false;
        };
    }

    public static Conflict conflict(String message) {
        return new Conflict(message);
    }

    public static TransientIo io(String message, Throwable cause) {
        return new TransientIo(message, cause);
    }

    public static ResourceLimit limit(String message) {
        return new ResourceLimit(message);
    }

    public static InvalidSchema invalid(String message) {
        return new InvalidSchema(message);
    }

    public static CorruptPage corrupt(long pageId, String message) {
        return new CorruptPage(pageId, message);
    }

    public static CorruptLog corruptLog(long lsn, String message) {
        return new CorruptLog(lsn, message);
    }

    public static final class Conflict extends HStoreException {
        Conflict(String message) {
            super(message, null);
        }

        @Override
        public Code code() {
            return Code.RETRYABLE_CONFLICT;
        }
    }

    public static final class TransientIo extends HStoreException {
        TransientIo(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public Code code() {
            return Code.RETRYABLE_IO;
        }
    }

    public static final class ResourceLimit extends HStoreException {
        ResourceLimit(String message) {
            super(message, null);
        }

        @Override
        public Code code() {
            return Code.ABORTED_RESOURCE_LIMIT;
        }
    }

    public static final class InvalidSchema extends HStoreException {
        InvalidSchema(String message) {
            super(message, null);
        }

        @Override
        public Code code() {
            return Code.INVALID_SCHEMA;
        }
    }

    public static final class CorruptPage extends HStoreException {
        private final long pageId;

        CorruptPage(long pageId, String message) {
            super(message + " [page " + PageId.unpack(pageId) + "]", null);
            this.pageId = pageId;
        }

        public long pageId() {
            return pageId;
        }

        @Override
        public Code code() {
            return Code.CORRUPT_PAGE;
        }
    }

    public static final class CorruptLog extends HStoreException {
        private final long lsn;

        CorruptLog(long lsn, String message) {
            super(message + " [lsn " + lsn + "]", null);
            this.lsn = lsn;
        }

        public long lsn() {
            return lsn;
        }

        @Override
        public Code code() {
            return Code.CORRUPT_LOG;
        }
    }
}
