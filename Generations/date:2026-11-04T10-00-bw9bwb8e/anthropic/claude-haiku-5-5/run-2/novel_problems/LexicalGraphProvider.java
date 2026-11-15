@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}


    private static final String[] EMPTY_WORDS = new String[0];
    private static final int[][] EMPTY_INDEX = new int[0][];

    // All published arrays are treated as immutable once published.
    // Mutable state is only read or written while holding stateMonitor.
    private final Object stateMonitor = new Object();
    private String[] vocabulary = EMPTY_WORDS;      // sorted, distinct, lowercase
    private long vocabularyRevision = 0L;
    private String[] tokens = EMPTY_WORDS;          // context tokens in order
    private String[] indexedWords = EMPTY_WORDS;    // sorted distinct context words
    private int[][] occurrences = EMPTY_INDEX;      // occurrences[i] = ascending token indexes of indexedWords[i]
    private long contextGeneration = 0L;

    public LexicalGraphProvider() {
    }

    /**
     * Policy: no operation blocks on resources. Expensive preparation happens outside
     * the state monitor; only the reference swap and counter update are guarded.
     * Counter overflow is reported with IllegalStateException before publication.
     */
    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new NullPointerException("words must not be null");
        }
        int n = words.length;
        String[] lower = new String[n];
        for (int i = 0; i < n; i++) {
            if (words[i] == null) {
                throw new NullPointerException("vocabulary entry must not be null");
            }
            lower[i] = normalizeWord(words[i]);
        }
        String[] prepared = distinctSorted(lower);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision counter overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new NullPointerException("sentence must not be null");
        }
        String[] toks = tokenize(sentence);
        int n = toks.length;
        int[] order = stableOrder(toks);

        int groups = 0;
        for (int k = 0; k < n; ) {
            int e = k + 1;
            while (e < n && toks[order[e]].equals(toks[order[k]])) {
                e++;
            }
            groups++;
            k = e;
        }

        String[] iw = new String[groups];
        int[][] occ = new int[groups][];
        int g = 0;
        for (int k = 0; k < n; ) {
            int e = k + 1;
            while (e < n && toks[order[e]].equals(toks[order[k]])) {
                e++;
            }
            iw[g] = toks[order[k]];
            occ[g] = new int[e - k];
            for (int t = k; t < e; t++) {
                occ[g][t - k] = order[t]; // stable sort keeps ascending indexes
            }
            g++;
            k = e;
        }

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation counter overflow");
            }
            tokens = toks;
            indexedWords = iw;
            occurrences = occ;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(vocabulary, vocabularyRevision,
                    tokens, indexedWords, occurrences, contextGeneration);
        }
    }

    public Snapshot snapshot() {
        String[] v;
        long vr;
        String[] t;
        String[] iw;
        int[][] occ;
        long cg;
        synchronized (stateMonitor) {
            v = vocabulary;
            vr = vocabularyRevision;
            t = tokens;
            iw = indexedWords;
            occ = occurrences;
            cg = contextGeneration;
        }
        return new Snapshot(v, vr, t, iw, occ, cg);
    }

    // ----------------------------------------------------------------------
    // Session: fully immutable pinned view; queries never touch the monitor.
    // ----------------------------------------------------------------------

    public static final class Session {
        private final String[] vocabulary;
        private final long vocabularyRevision;
        private final String[] tokens;
        private final String[] indexedWords;
        private final int[][] occurrences;
        private final long contextGeneration;

        private Session(String[] vocabulary, long vocabularyRevision,
                        String[] tokens, String[] indexedWords, int[][] occurrences,
                        long contextGeneration) {
            this.vocabulary = vocabulary;
            this.vocabularyRevision = vocabularyRevision;
            this.tokens = tokens;
            this.indexedWords = indexedWords;
            this.occurrences = occurrences;
            this.contextGeneration = contextGeneration;
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public long contextGeneration() {
            return contextGeneration;
        }

        /**
         * Returns distinct pinned vocabulary words with the given prefix that occur in the
         * pinned context, sorted by decreasing length, then ascending lexical order.
         */
        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix must not be null");
            }
            String p;
            try {
                p = normalizeWord(prefix, true);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid prefix", ex);
            }

            // Vocabulary is sorted, so words with prefix p form a contiguous range.
            int lo = lowerBound(vocabulary, p);
            int end = lo;
            while (end < vocabulary.length && vocabulary[end].startsWith(p)) {
                end++;
            }

            String[] found = new String[end - lo];
            int m = 0;
            int maxLen = 0;
            for (int i = lo; i < end; i++) {
                String w = vocabulary[i];
                if (find(indexedWords, w) >= 0) {
                    found[m++] = w;
                    if (w.length() > maxLen) {
                        maxLen = w.length();
                    }
                }
            }

            // Stable bucket ordering: length descending, lexical ascending within a length
            // (found is already lexically sorted).
            int[] cnt = new int[maxLen + 1];
            for (int k = 0; k < m; k++) {
                cnt[found[k].length()]++;
            }
            int[] start = new int[maxLen + 1];
            int pos = 0;
            for (int len = maxLen; len >= 1; len--) {
                start[len] = pos;
                pos += cnt[len];
            }
            String[] result = new String[m];
            for (int k = 0; k < m; k++) {
                int len = found[k].length();
                result[start[len]++] = found[k];
            }
            return result;
        }

        /**
         * Returns ascending indexes j > tokenIndex whose token belongs to the pinned vocabulary.
         */
        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("token index out of range: " + tokenIndex);
            }
            int capacity = tokens.length - tokenIndex - 1;
            int[] tmp = new int[capacity];
            int c = 0;
            for (int j = tokenIndex + 1; j < tokens.length; j++) {
                if (find(vocabulary, tokens[j]) >= 0) {
                    tmp[c++] = j;
                }
            }
            int[] out = new int[c];
            System.arraycopy(tmp, 0, out, 0, c);
            return out;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocabulary, vocabularyRevision, tokens,
                    indexedWords, occurrences, contextGeneration);
        }
    }

    // ----------------------------------------------------------------------
    // Snapshot: detached copies of all state; getters return fresh copies.
    // ----------------------------------------------------------------------

    public static final class Snapshot {
        private final String[] vocabulary;
        private final long vocabularyRevision;
        private final String[] tokens;
        private final String[] indexedWords;
        private final int[][] occurrences;
        private final long contextGeneration;

        private Snapshot(String[] vocabulary, long vocabularyRevision,
                         String[] tokens, String[] indexedWords, int[][] occurrences,
                         long contextGeneration) {
            this.vocabulary = copyStrings(vocabulary);
            this.vocabularyRevision = vocabularyRevision;
            this.tokens = copyStrings(tokens);
            this.indexedWords = copyStrings(indexedWords);
            this.occurrences = copyIndex(occurrences);
            this.contextGeneration = contextGeneration;
        }

        public String[] vocabulary() {
            return copyStrings(vocabulary);
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public String[] tokens() {
            return copyStrings(tokens);
        }

        public String[] indexedWords() {
            return copyStrings(indexedWords);
        }

        public int[][] occurrenceIndexes() {
            return copyIndex(occurrences);
        }

        public long contextGeneration() {
            return contextGeneration;
        }
    }

    // ----------------------------------------------------------------------
    // Helpers (no library classes; only java.lang and arrays).
    // ----------------------------------------------------------------------

    private static String normalizeWord(String word) {
        return normalizeWord(word, false);
    }

    /**
     * Validates that word is a nonempty (unless allowEmpty) ASCII-letter string and
     * returns its lowercase form.
     */
    private static String normalizeWord(String word, boolean allowEmpty) {
        int len = word.length();
        if (len == 0 && !allowEmpty) {
            throw new IllegalArgumentException("vocabulary entry must be nonempty");
        }
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            char c = word.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("invalid character in word: " + word);
            }
            buf[i] = lowerAscii(c);
        }
        return new String(buf);
    }

    private static String[] distinctSorted(String[] values) {
        int n = values.length;
        int[] order = stableOrder(values);
        int distinct = 0;
        for (int k = 0; k < n; k++) {
            if (k == 0 || !values[order[k]].equals(values[order[k - 1]])) {
                distinct++;
            }
        }
        String[] out = new String[distinct];
        int d = 0;
        for (int k = 0; k < n; k++) {
            if (k == 0 || !values[order[k]].equals(values[order[k - 1]])) {
                out[d++] = values[order[k]];
            }
        }
        return out;
    }

    /** Stable merge sort of indexes by keys' natural order. */
    private static int[] stableOrder(String[] keys) {
        int n = keys.length;
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        int[] tmp = new int[n];
        mergeSort(perm, tmp, 0, n, keys);
        return perm;
    }

    private static void mergeSort(int[] a, int[] tmp, int lo, int hi, String[] keys) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid, keys);
        mergeSort(a, tmp, mid, hi, keys);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (keys[a[j]].compareTo(keys[a[i]]) < 0) {
                tmp[k++] = a[j++];
            } else {
                tmp[k++] = a[i++];
            }
        }
        while (i < mid) {
            tmp[k++] = a[i++];
        }
        while (j < hi) {
            tmp[k++] = a[j++];
        }
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }

    private static String[] tokenize(String s) {
        String[] out = new String[8];
        int n = 0;
        int i = 0;
        int len = s.length();
        while (i < len) {
            if (!isAsciiLetter(s.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < len && isAsciiLetter(s.charAt(i))) {
                i++;
            }
            if (n == out.length) {
                String[] bigger = new String[out.length * 2];
                System.arraycopy(out, 0, bigger, 0, n);
                out = bigger;
            }
            char[] buf = new char[i - start];
            for (int k = start; k < i; k++) {
                buf[k - start] = lowerAscii(s.charAt(k));
            }
            out[n++] = new String(buf);
        }
        String[] result = new String[n];
        System.arraycopy(out, 0, result, 0, n);
        return result;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static char lowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    /** Index of first element >= key in a sorted array. */
    private static int lowerBound(String[] sorted, String key) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid].compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Binary search in a sorted array; returns index or -1. */
    private static int find(String[] sorted, String key) {
        int idx = lowerBound(sorted, key);
        if (idx < sorted.length && sorted[idx].equals(key)) {
            return idx;
        }
        return -1;
    }

    private static String[] copyStrings(String[] src) {
        String[] out = new String[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static int[][] copyIndex(int[][] src) {
        int[][] out = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = new int[src[i].length];
            System.arraycopy(src[i], 0, out[i], 0, src[i].length);
        }
        return out;
    }
}
