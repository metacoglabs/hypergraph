package io.hstore.db.query;

public record Token(Kind kind, String text, int position) {

    public enum Kind { WORD, VARIABLE, NUMBER, STRING, SYMBOL, END }

    public boolean is(String word) {
        return (kind == Kind.WORD || kind == Kind.SYMBOL) && text.equalsIgnoreCase(word);
    }

    @Override
    public String toString() {
        return kind == Kind.END ? "end of input" : "'" + text + "'";
    }
}
