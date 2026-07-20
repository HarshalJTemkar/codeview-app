package com.example.util;

import java.util.List;

/**
 * Small sample class so the shipped project has something to index out of
 * the box. Point codeview.repo-root at your own codebase to index that
 * instead.
 */
public class MathUtils {

    public int sum(List<Integer> numbers) {
        int total = 0;
        for (int n : numbers) {
            total += n;
        }
        return total;
    }

    public double average(List<Integer> numbers) {
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("Cannot average an empty list");
        }
        return sum(numbers) / (double) numbers.size();
    }
}
