// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.engine.page.ByteCursor;

import java.util.Arrays;

public record Signature(int edgeType, int[] labels, int[] counts) {

    public Signature {
        if (labels.length != counts.length) {
            throw new IllegalArgumentException("signature labels and counts differ in length");
        }
        labels = labels.clone();
        counts = counts.clone();
    }

    public static Signature empty(int edgeType) {
        return new Signature(edgeType, new int[0], new int[0]);
    }

    public int arity() {
        return Arrays.stream(counts).sum();
    }

    public Signature withAdded(int label) {
        int at = Arrays.binarySearch(labels, label);
        if (at >= 0) {
            int[] more = counts.clone();
            more[at]++;
            return new Signature(edgeType, labels, more);
        }
        int insert = -at - 1;
        int[] nextLabels = new int[labels.length + 1];
        int[] nextCounts = new int[counts.length + 1];
        System.arraycopy(labels, 0, nextLabels, 0, insert);
        System.arraycopy(counts, 0, nextCounts, 0, insert);
        nextLabels[insert] = label;
        nextCounts[insert] = 1;
        System.arraycopy(labels, insert, nextLabels, insert + 1, labels.length - insert);
        System.arraycopy(counts, insert, nextCounts, insert + 1, counts.length - insert);
        return new Signature(edgeType, nextLabels, nextCounts);
    }

    public Signature withRemoved(int label) {
        int at = Arrays.binarySearch(labels, label);
        if (at < 0) {
            throw new IllegalArgumentException("signature has no label " + label);
        }
        if (counts[at] > 1) {
            int[] fewer = counts.clone();
            fewer[at]--;
            return new Signature(edgeType, labels, fewer);
        }
        int[] nextLabels = new int[labels.length - 1];
        int[] nextCounts = new int[counts.length - 1];
        System.arraycopy(labels, 0, nextLabels, 0, at);
        System.arraycopy(counts, 0, nextCounts, 0, at);
        System.arraycopy(labels, at + 1, nextLabels, at, labels.length - at - 1);
        System.arraycopy(counts, at + 1, nextCounts, at, counts.length - at - 1);
        return new Signature(edgeType, nextLabels, nextCounts);
    }

    public int encodedSize() {
        int size = ByteCursor.varLongSize(edgeType) + ByteCursor.varLongSize(labels.length);
        for (int i = 0; i < labels.length; i++) {
            size += ByteCursor.varLongSize(labels[i]) + ByteCursor.varLongSize(counts[i]);
        }
        return size;
    }

    public void writeTo(ByteCursor out) {
        out.putVarInt(edgeType).putVarInt(labels.length);
        for (int i = 0; i < labels.length; i++) {
            out.putVarInt(labels[i]).putVarInt(counts[i]);
        }
    }

    public static Signature readFrom(ByteCursor in) {
        int edgeType = in.getVarInt();
        int n = in.getVarInt();
        int[] labels = new int[n];
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            labels[i] = in.getVarInt();
            counts[i] = in.getVarInt();
        }
        return new Signature(edgeType, labels, counts);
    }

    public byte[] canonicalBytes() {
        ByteCursor out = ByteCursor.growable(encodedSize());
        writeTo(out);
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Signature(int type, int[] otherLabels, int[] otherCounts)
                && type == edgeType && Arrays.equals(labels, otherLabels) && Arrays.equals(counts, otherCounts);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * edgeType + Arrays.hashCode(labels)) + Arrays.hashCode(counts);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Signature[type " + edgeType + ":");
        for (int i = 0; i < labels.length; i++) {
            text.append(' ').append(labels[i]).append('x').append(counts[i]);
        }
        return text.append(']').toString();
    }
}
