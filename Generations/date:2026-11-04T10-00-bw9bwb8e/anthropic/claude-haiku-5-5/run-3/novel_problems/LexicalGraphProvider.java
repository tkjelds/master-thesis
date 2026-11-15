@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}


    private static final String[] NO_WORDS = new String[0];
    private static final int[][] NO_POSITIONS = new int[0][];

    // Guards only the publication and capture of the immutable state reference.
    // All expensive preparation happens outside this monitor.
    private final Object monitor = new Object();
    private State state; // guarded by monitor

    public LexicalGraphProvider() {
        this.state = new State(
                new Vocabulary(0L, NO_WORDS),
                new Context(0L, new Index(NO_WORDS, NO_WORDS, NO_POSITIONS)));
    }

    /**
     * Replaces the vocabulary. Validation and preparation happen outside the monitor;
     * only the version increment and reference publication happen inside it.
     * This method never blocks waiting for a condition: it either publishes or throws.
     */
    public void replaceVocabulary(String[] words) {
        String[] prepared = prepareVocabulary(words);
        synchronized (monitor) {
            long revision = state.vocabulary.revision;
            if (revision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision counter overflow");
            }
            state = new State(new Vocabulary(revision + 1L, prepared), state.context);
        }
    }

    /**
     * Replaces the context sentence. Tokenization and occurrence indexing happen outside
     * the monitor; only the generation increment and publication happen inside it.
     */
    public void setContext(String sentence) {
        if (sentence == null) {
            throw new NullPointerException("sentence");
        }
        Index index = buildIndex(tokenize(sentence));
        synchronized (monitor) {
            long generation = state.context.generation;
            if (generation == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation counter overflow");
            }
            state = new State(state.vocabulary, new Context(generation + 1L, index));
        }
    }

    /** Atomically captures a consistent pair of vocabulary and context versions. */
    public Session openSession() {
        State pinned;
        synchronized (monitor) {
            pinned = state;
        }
        return new Session(pinned);
    }

    /** Returns a detached snapshot of the current provider state. */
    public Snapshot snapshot() {
        State current;
        synchronized (monitor) {
            current = state;
        }
        return createSnapshot(current);
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    public static final class Session {
        private final State pinned;

        private Session(State pinned) {
            this.pinned = pinned;
        }

        public long vocabularyRevision() {
            return pinned.vocabulary.revision;
        }

        public long contextGeneration() {
            return pinned.context.generation;
        }

        /**
         * Returns distinct pinned vocabulary words with the given prefix that occur in the
         * pinned context, sorted by decreasing length then ascending lexical order.
         */
        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix must not be null");
            }
            String p = normalizeLetters(prefix);
            String[] words = pinned.vocabulary.words;
            String[] distinct = pinned.context.index.distinct;
            String[] tmp = new String[words.length];
            int count = 0;
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                if (w.startsWith(p) && indexOf(distinct, w) >= 0) {
                    tmp[count++] = w;
                }
            }
            String[] result = new String[count];
            System.arraycopy(tmp, 0, result, 0, count);
            mergeSort(result, true);
            return result;
        }

        /**
         * Returns the increasing indexes of all later pinned-context tokens that belong
         * to the pinned vocabulary.
         */
        public int[] dependencies(int tokenIndex) {
            String[] tokens = pinned.context.index.tokens;
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("token index out of range: " + tokenIndex);
            }
            String[] words = pinned.vocabulary.words;
            int count = 0;
            for (int j = tokenIndex + 1; j < tokens.length; j++) {
                if (indexOf(words, tokens[j]) >= 0) {
                    count++;
                }
            }
            int[] result = new int[count];
            int k = 0;
            for (int j = tokenIndex + 1; j < tokens.length; j++) {
                if (indexOf(words, tokens[j]) >= 0) {
                    result[k++] = j;
                }
            }
            return result;
        }

        /** Returns a detached snapshot of this session's pinned state. */
        public Snapshot snapshot() {
            return createSnapshot(pinned);
        }
    }

    // ------------------------------------------------------------------
    // Snapshot (detached copies only)
    // ------------------------------------------------------------------

    public static final class Snapshot {
        private final long vocabularyRevision;
        private final long contextGeneration;
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] indexedWords;
        private final int[][] occurrences;

        private Snapshot(long vocabularyRevision, long contextGeneration, String[] vocabulary,
                         String[] tokens, String[] indexedWords, int[][] occurrences) {
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.indexedWords = indexedWords;
            this.occurrences = occurrences;
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public long contextGeneration() {
            return contextGeneration;
        }

        public String[] vocabulary() {
            return vocabulary.clone();
        }

        public String[] tokens() {
            return tokens.clone();
        }

        /** Distinct context tokens in ascending order; aligned with occurrences(). */
        public String[] indexedWords() {
            return indexedWords.clone();
        }

        /** Occurrence indexes per indexed word, aligned with indexedWords(). */
        public int[][] occurrences() {
            int[][] copy = new int[occurrences.length][];
            for (int i = 0; i < occurrences.length; i++) {
                copy[i] = occurrences[i].clone();
            }
            return copy;
        }
    }

    // ------------------------------------------------------------------
    // Immutable internal state
    // ------------------------------------------------------------------

    private static final class State {
        final Vocabulary vocabulary;
        final Context context;

        State(Vocabulary vocabulary, Context context) {
            this.vocabulary = vocabulary;
            this.context = context;
        }
    }

    private static final class Vocabulary {
        final long revision;
        final String[] words; // sorted, distinct, never mutated after publication

        Vocabulary(long revision, String[] words) {
            this.revision = revision;
            this.words = words;
        }
    }

    private static final class Context {
        final long generation;
        final Index index;

        Context(long generation, Index index) {
            this.generation = generation;
            this.index = index;
        }
    }

    private static final class Index {
        final String[] tokens;     // ordered context tokens
        final String[] distinct;   // sorted distinct tokens
        final int[][] positions;   // positions[k] = ascending occurrence indexes of distinct[k]

        Index(String[] tokens, String[] distinct, int[][] positions) {
            this.tokens = tokens;
            this.distinct = distinct;
            this.positions = positions;
        }
    }

    // ------------------------------------------------------------------
    // Helpers (pure functions, no shared mutable state)
    // ------------------------------------------------------------------

    private static Snapshot createSnapshot(State s) {
        String[] vocab = s.vocabulary.words.clone();
        String[] tokens = s.context.index.tokens.clone();
        String[] distinct = s.context.index.distinct.clone();
        int[][] pos = new int[distinct.length][];
        for (int i = 0; i < distinct.length; i++) {
            pos[i] = s.context.index.positions[i].clone();
        }
        return new Snapshot(s.vocabulary.revision, s.context.generation, vocab, tokens, distinct, pos);
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new NullPointerException("words must not be null");
        }
        String[] normalized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w == null) {
                throw new NullPointerException("vocabulary entry must not be null");
            }
            normalized[i] = normalizeWord(w);
        }
        mergeSort(normalized, false);
        return dedupeSorted(normalized);
    }

    private static String normalizeWord(String w) {
        if (w.length() == 0) {
            throw new IllegalArgumentException("vocabulary entry must not be empty");
        }
        return normalizeLetters(w);
    }

    /** Lowercases an ASCII-letter string; throws IllegalArgumentException otherwise. Empty allowed. */
    private static String normalizeLetters(String s) {
        int n = s.length();
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                out[i] = (char) (c + 32);
            } else if (c >= 'a' && c <= 'z') {
                out[i] = c;
            } else {
                throw new IllegalArgumentException("non-ASCII-letter character in input: " + c);
            }
        }
        return new String(out);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    private static String[] tokenize(String s) {
        int n = s.length();
        int count = 0;
        boolean inRun = false;
        for (int i = 0; i < n; i++) {
            boolean letter = isAsciiLetter(s.charAt(i));
            if (letter && !inRun) {
                count++;
            }
            inRun = letter;
        }
        String[] tokens = new String[count];
        int k = 0;
        int i = 0;
        while (i < n) {
            if (!isAsciiLetter(s.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isAsciiLetter(s.charAt(i))) {
                i++;
            }
            char[] buf = new char[i - start];
            for (int p = start; p < i; p++) {
                buf[p - start] = toLowerAscii(s.charAt(p));
            }
            tokens[k++] = new String(buf);
        }
        return tokens;
    }

    private static Index buildIndex(String[] tokens) {
        String[] sorted = tokens.clone();
        mergeSort(sorted, false);
        String[] distinct = dedupeSorted(sorted);

        int[] slot = new int[tokens.length];
        int[] counts = new int[distinct.length];
        for (int j = 0; j < tokens.length; j++) {
            int d = indexOf(distinct, tokens[j]);
            slot[j] = d;
            counts[d]++;
        }
        int[][] positions = new int[distinct.length][];
        for (int d = 0; d < distinct.length; d++) {
            positions[d] = new int[counts[d]];
        }
        int[] fill = new int[distinct.length];
        for (int j = 0; j < tokens.length; j++) {
            int d = slot[j];
            positions[d][fill[d]++] = j; // ascending by construction
        }
        return new Index(tokens, distinct, positions);
    }

    private static String[] dedupeSorted(String[] sorted) {
        int unique = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || !sorted[i].equals(sorted[i - 1])) {
                unique++;
            }
        }
        String[] out = new String[unique];
        int k = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || !sorted[i].equals(sorted[i - 1])) {
                out[k++] = sorted[i];
            }
        }
        return out;
    }

    /** Binary search over a lexically sorted array; returns index or -1. */
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

    private static int compareWords(String a, String b, boolean lengthDescending) {
        if (lengthDescending && a.length() != b.length()) {
            return b.length() - a.length();
        }
        return a.compareTo(b);
    }

    private static void mergeSort(String[] a, boolean lengthDescending) {
        if (a.length < 2) {
            return;
        }
        String[] tmp = new String[a.length];
        sortRange(a, tmp, 0, a.length, lengthDescending);
    }

    private static void sortRange(String[] a, String[] tmp, int lo, int hi, boolean ld) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        sortRange(a, tmp, lo, mid, ld);
        sortRange(a, tmp, mid, hi, ld);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (compareWords(a[j], a[i], ld) < 0) {
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
}
