package io.github.tendo505.simpan.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiled LIKE pattern: literal code points, {@code _} (any one), and
 * {@code %} (any run), with backslash escaping either. Matching is the
 * classic greedy wildcard algorithm that only ever backs up to the most
 * recent {@code %}, so it takes at most O(text × pattern) steps; a
 * backtracking regex can take exponential time on patterns like
 * {@code %a%a%a%b}.
 */
public final class LikePattern {
    private static final int ANY_ONE = -1;
    private static final int ANY_RUN = -2;

    private final int[] tokens;
    private final boolean caseInsensitive;

    private LikePattern(int[] tokens, boolean caseInsensitive) {
        this.tokens = tokens;
        this.caseInsensitive = caseInsensitive;
    }

    public static LikePattern compile(String pattern, boolean caseInsensitive) {
        List<Integer> out = new ArrayList<>();
        int[] cps = pattern.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int c = cps[i];
            if (c == '\\' && i + 1 < cps.length) {
                out.add(fold(cps[++i], caseInsensitive));
            } else if (c == '%') {
                if (out.isEmpty() || out.get(out.size() - 1) != ANY_RUN) out.add(ANY_RUN); // %% is %
            } else if (c == '_') {
                out.add(ANY_ONE);
            } else {
                out.add(fold(c, caseInsensitive));
            }
        }
        int[] tokens = new int[out.size()];
        for (int i = 0; i < tokens.length; i++) tokens[i] = out.get(i);
        return new LikePattern(tokens, caseInsensitive);
    }

    private static int fold(int c, boolean ci) {
        return ci ? Character.toLowerCase(Character.toUpperCase(c)) : c;
    }

    public boolean matches(String text) {
        int[] s = text.codePoints().toArray();
        if (caseInsensitive) for (int i = 0; i < s.length; i++) s[i] = fold(s[i], true);
        int[] p = tokens;
        int i = 0;
        int j = 0;
        int star = -1;
        int resume = 0;
        while (i < s.length) {
            if (j < p.length && (p[j] == ANY_ONE || p[j] == s[i])) {
                i++;
                j++;
            } else if (j < p.length && p[j] == ANY_RUN) {
                star = j++;
                resume = i; // first try letting % match nothing
            } else if (star >= 0) {
                j = star + 1;
                i = ++resume; // let the last % swallow one more character
            } else {
                return false;
            }
        }
        while (j < p.length && p[j] == ANY_RUN) j++;
        return j == p.length;
    }
}
