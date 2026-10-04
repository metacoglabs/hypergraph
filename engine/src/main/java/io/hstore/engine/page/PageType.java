package io.hstore.engine.page;

public enum PageType {
    INTERNAL(1),
    LEAF(2);

    private final int code;

    PageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PageType of(int code) {
        return switch (code) {
            case 1 -> INTERNAL;
            case 2 -> LEAF;
            default -> throw new IllegalArgumentException("unknown page type " + code);
        };
    }
}
