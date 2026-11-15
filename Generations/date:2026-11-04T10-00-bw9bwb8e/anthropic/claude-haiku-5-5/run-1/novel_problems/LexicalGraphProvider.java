@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}


    private final Object stateLock = new Object();

    // All fields below are guarded by stateLock. The referenced data objects are immutable.
    private VocabularyData vocabulary = VocabularyData.EMPTY;
    private ContextData context = ContextData.EMPTY;
    private long vocabularyRevision = 0L;
    private long contextGeneration = 0L;

    public LexicalGraphProvider() {
    }

    /**
     * Policy: no operation waits for a condition. Expensive preparation happens outside the
     * monitor; only the reference swap and counter advance happen inside it. Overflow of a
     * version counter is rejected with IllegalStateException before any publication.
     */
    public void replaceVocabulary(String[] words) {
        VocabularyData prepared = VocabularyData.build(words);
        synchronized (stateLock) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision counter overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        ContextData prepared = ContextData.build(sentence);
        synchronized (stateLock) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation counter overflow");
            }
            context = prepared;
            contextGeneration++;
        }
    }

    public Session openSession() {
        VocabularyData v;
        ContextData c;
        long vr;
        long cg;
        synchronized (stateLock) {
            v = vocabulary;
            c = context;
            vr = vocabularyRevision;
            cg = contextGeneration;
        }
        return new Session(v, vr, c, cg);
    }

    public Snapshot snapshot() {
        VocabularyData v;
        ContextData c;
        long vr;
        long cg;
        synchronized (stateLock) {
            v = vocabulary;
            c = context;
            vr = vocabularyRevision;
            cg = contextGeneration;
        }
        return Snapshot.of(v, vr, c, cg);
    }

    // ------------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------------

    public static final class Session {
        private final VocabularyData vocabulary;
        private final long vocabularyRevision;
        private final ContextData context;
        private final long contextGeneration;

        private Session(VocabularyData vocabulary, long vocabularyRevision,
                        ContextData context, long contextGeneration) {
            this.vocabulary = vocabulary;
            this.vocabularyRevision = vocabularyRevision;
            this.context = context;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String p = normalizePrefix(prefix);
            String[] words = vocabulary.words;

            int start = lowerBound(words, p);
            String[] tmp = new String[words.length - start];
            int count = 0;
            for (int j = start; j < words.length && words[j].startsWith(p); j++) {
                if (context.indexOfToken(words[j]) >= 0) {
                    tmp[count++] = words[j];
                }
            }
            String[] result = new String[count];
            System.arraycopy(tmp, 0, result, 0, count);
            mergeSortWords(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            String[] tokens = context.tokens;
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("token index out of range: " + tokenIndex);
            }
            int[] tmp = new int[tokens.length - tokenIndex - 1];
            int count = 0;
            for (int j = tokenIndex + 1; j < tokens.length; j++) {
                if (vocabulary.contains(tokens[j])) {
                    tmp[count++] = j;
                }
            }
            int[] result = new int[count];
            System.arraycopy(tmp, 0, result, 0, count);
            return result;
        }

        public Snapshot snapshot() {
            return Snapshot.of(vocabulary, vocabularyRevision, context, contextGeneration);
        }
    }

    // ------------------------------------------------------------------------
    // Snapshot (detached copies only)
    // ------------------------------------------------------------------------

    public static final class Snapshot {
        private final long vocabularyRevision;
        private final long contextGeneration;
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceTokens;
        private final int[][] occurrenceIndexes;

        private Snapshot(long vocabularyRevision, long contextGeneration, String[] vocabulary,
                         String[] tokens, String[] occurrenceTokens, int[][] occurrenceIndexes) {
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceTokens = occurrenceTokens;
            this.occurrenceIndexes = occurrenceIndexes;
        }

        static Snapshot of(VocabularyData v, long vr, ContextData c, long cg) {
            return new Snapshot(vr, cg,
                    copyStrings(v.words),
                    copyStrings(c.tokens),
                    copyStrings(c.distinct),
                    deepCopy(c.occurrences));
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }

        public String[] getVocabulary() {
            return copyStrings(vocabulary);
        }

        public String[] getTokens() {
            return copyStrings(tokens);
        }

        public String[] getOccurrenceTokens() {
            return copyStrings(occurrenceTokens);
        }

        public int[][] getOccurrenceIndexes() {
            return deepCopy(occurrenceIndexes);
        }
    }

    // ------------------------------------------------------------------------
    // Immutable prepared vocabulary
    // ------------------------------------------------------------------------

    private static final class VocabularyData {
        static final VocabularyData EMPTY = new VocabularyData(new String[0]);

        final String[] words; // lowercase, sorted ascending, unique

        private VocabularyData(String[] words) {
            this.words = words;
        }

        static VocabularyData build(String[] input) {
            if (input == null) {
                throw new IllegalArgumentException("vocabulary array is null");
            }
            String[] norm = new String[input.length];
            for (int i = 0; i < input.length; i++) {
                String w = input[i];
                if (w == null) {
                    throw new IllegalArgumentException("vocabulary entry is null at " + i);
                }
                if (!isAsciiLetterString(w)) {
                    throw new IllegalArgumentException("invalid vocabulary entry: " + w);
                }
                norm[i] = lowerAscii(w);
            }
            mergeSortWords(norm, false);
            int count = 0;
            for (int i = 0; i < norm.length; i++) {
                if (count == 0 || !norm[count - 1].equals(norm[i])) {
                    norm[count++] = norm[i];
                }
            }
            String[] unique = new String[count];
            System.arraycopy(norm, 0, unique, 0, count);
            return new VocabularyData(unique);
        }

        boolean contains(String word) {
            return indexOf(words, word) >= 0;
        }
    }

    // ------------------------------------------------------------------------
    // Immutable prepared context with occurrence index
    // ------------------------------------------------------------------------

    private static final class ContextData {
        static final ContextData EMPTY =
                new ContextData(new String[0], new String[0], new int[0][]);

        final String[] tokens;          // ordered lowercase tokens
        final String[] distinct;        // sorted distinct tokens
        final int[][] occurrences;      // occurrences[k] = ascending indexes of distinct[k]

        private ContextData(String[] tokens, String[] distinct, int[][] occurrences) {
            this.tokens = tokens;
            this.distinct = distinct;
            this.occurrences = occurrences;
        }

        static ContextData build(String sentence) {
            if (sentence == null) {
                throw new IllegalArgumentException("sentence is null");
            }
            int n = sentence.length();
            String[] toks = new String[(n + 1) / 2];
            int count = 0;
            int i = 0;
            while (i < n) {
                if (isAsciiLetter(sentence.charAt(i))) {
                    int start = i;
                    while (i < n && isAsciiLetter(sentence.charAt(i))) {
                        i++;
                    }
                    toks[count++] = lowerAscii(sentence.substring(start, i));
                } else {
                    i++;
                }
            }
            String[] tokens = new String[count];
            System.arraycopy(toks, 0, tokens, 0, count);

            int[] order = new int[count];
            for (int k = 0; k < count; k++) {
                order[k] = k;
            }
            mergeSortIndexes(order, tokens);

            int groups = 0;
            for (int k = 0; k < count; k++) {
                if (k == 0 || !tokens[order[k]].equals(tokens[order[k - 1]])) {
                    groups++;
                }
            }
            String[] distinct = new String[groups];
            int[][] occ = new int[groups][];
            int g = 0;
            int k = 0;
            while (k < count) {
                int end = k;
                String t = tokens[order[k]];
                while (end < count && tokens[order[end]].equals(t)) {
                    end++;
                }
                distinct[g] = t;
                occ[g] = new int[end - k];
                for (int q = k; q < end; q++) {
                    occ[g][q - k] = order[q]; // ascending because sort is stable on index order
                }
                g++;
                k = end;
            }
            return new ContextData(tokens, distinct, occ);
        }

        int indexOfToken(String token) {
            return indexOf(distinct, token);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix is null");
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (!isAsciiLetter(prefix.charAt(i))) {
                throw new IllegalArgumentException("invalid prefix: " + prefix);
            }
        }
        return lowerAscii(prefix);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiLetterString(String s) {
        if (s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isAsciiLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String lowerAscii(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] >= 'A' && cs[i] <= 'Z') {
                cs[i] = (char) (cs[i] + ('a' - 'A'));
            }
        }
        return new String(cs);
    }

    /** Binary search on a lexically sorted array; returns index or -1. */
    private static int indexOf(String[] sorted, String key) {
        int lo = 0;
        int hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = sorted[mid].compareTo(key);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** First index whose element is >= key lexically. */
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

    private static int compareWords(String a, String b, boolean byLengthDesc) {
        if (byLengthDesc && a.length() != b.length()) {
            return b.length() - a.length();
        }
        return a.compareTo(b);
    }

    /** Stable bottom-up merge sort of strings. */
    private static void mergeSortWords(String[] a, boolean byLengthDesc) {
        int n = a.length;
        if (n < 2) {
            return;
        }
        String[] src = a;
        String[] dst = new String[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    if (compareWords(src[j], src[i], byLengthDesc) < 0) {
                        dst[k++] = src[j++];
                    } else {
                        dst[k++] = src[i++];
                    }
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            String[] t = src;
            src = dst;
            dst = t;
        }
        if (src != a) {
            System.arraycopy(src, 0, a, 0, n);
        }
    }

    /** Stable bottom-up merge sort of indexes by tokens[index]; ties keep ascending index order. */
    private static void mergeSortIndexes(int[] a, String[] tokens) {
        int n = a.length;
        if (n < 2) {
            return;
        }
        int[] src = a;
        int[] dst = new int[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    if (tokens[src[j]].compareTo(tokens[src[i]]) < 0) {
                        dst[k++] = src[j++];
                    } else {
                        dst[k++] = src[i++];
                    }
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            int[] t = src;
            src = dst;
            dst = t;
        }
        if (src != a) {
            System.arraycopy(src, 0, a, 0, n);
        }
    }

    private static String[] copyStrings(String[] src) {
        String[] out = new String[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static int[][] deepCopy(int[][] src) {
        int[][] out = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = new int[src[i].length];
            System.arraycopy(src[i], 0, out[i], 0, src[i].length);
        }
        return out;
    }
}
