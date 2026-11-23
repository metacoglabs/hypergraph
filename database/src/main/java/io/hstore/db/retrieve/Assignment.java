// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import java.util.Arrays;

final class Assignment {

    static final double FORBIDDEN = 1e12;

    private Assignment() {
    }

    static int[] solve(double[][] cost) {
        int n = cost.length;
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int column = 0;
            double[] min = new double[n + 1];
            boolean[] used = new boolean[n + 1];
            Arrays.fill(min, Double.POSITIVE_INFINITY);
            do {
                used[column] = true;
                int row = p[column];
                double delta = Double.POSITIVE_INFINITY;
                int next = 0;
                for (int j = 1; j <= n; j++) {
                    if (!used[j]) {
                        double reduced = cost[row - 1][j - 1] - u[row] - v[j];
                        if (reduced < min[j]) {
                            min[j] = reduced;
                            way[j] = column;
                        }
                        if (min[j] < delta) {
                            delta = min[j];
                            next = j;
                        }
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        min[j] -= delta;
                    }
                }
                column = next;
            } while (p[column] != 0);
            do {
                int previous = way[column];
                p[column] = p[previous];
                column = previous;
            } while (column != 0);
        }
        int[] rowToColumn = new int[n];
        for (int j = 1; j <= n; j++) {
            rowToColumn[p[j] - 1] = j - 1;
        }
        return rowToColumn;
    }

    static double minimum(double[][] cost) {
        int[] assignment = solve(cost);
        double total = 0;
        for (int i = 0; i < cost.length; i++) {
            total += cost[i][assignment[i]];
        }
        return total;
    }

    static double[][] withDeletions(double[][] substitute, double[] delete, double[] insert) {
        int n = delete.length;
        int m = insert.length;
        double[][] matrix = new double[n + m][n + m];
        for (double[] row : matrix) {
            Arrays.fill(row, FORBIDDEN);
        }
        for (int i = 0; i < n; i++) {
            System.arraycopy(substitute[i], 0, matrix[i], 0, m);
            matrix[i][m + i] = delete[i];
        }
        for (int j = 0; j < m; j++) {
            matrix[n + j][j] = insert[j];
            for (int i = 0; i < n; i++) {
                matrix[n + j][m + i] = 0;
            }
        }
        return matrix;
    }
}
