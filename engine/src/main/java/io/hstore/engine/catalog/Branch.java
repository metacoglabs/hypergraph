package io.hstore.engine.catalog;

public record Branch(int id, String name, int parent, long baseGeneration, long createdAt, State state, RootVector roots) {

    public enum State { ACTIVE, MERGED, DROPPED }

    public static final int MAIN = 0;

    public static Branch main(RootVector roots) {
        return new Branch(MAIN, "main", MAIN, 0, 0, State.ACTIVE, roots);
    }

    public Branch withRoots(RootVector next) {
        return new Branch(id, name, parent, baseGeneration, createdAt, state, next);
    }

    public Branch withState(State next) {
        return new Branch(id, name, parent, baseGeneration, createdAt, next, roots);
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }
}
