package io.github.tendo505.simpan.storage;

/** Where a row version lives: page and slot within a table's heap file. */
public record Rid(int page, int slot) implements Comparable<Rid> {
    public static final int BYTES = 6;

    public void write(byte[] out, int at) {
        Page.putInt(out, at, page);
        Page.putU16(out, at + 4, slot);
    }

    public static Rid read(byte[] in, int at) {
        return new Rid(Page.getInt(in, at), Page.getU16(in, at + 4));
    }

    @Override
    public int compareTo(Rid o) {
        int c = Integer.compare(page, o.page);
        return c != 0 ? c : Integer.compare(slot, o.slot);
    }

    @Override
    public String toString() {
        return "(" + page + "," + slot + ")";
    }
}
