import java.lang.String;

@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}


    private static final class State {
        final String[] vocab;       // sorted, distinct, lowercase
        final long vocabRevision;
        final String[] tokens;      // ordered tokens
        final String[] indexWords;  // sorted distinct token words
        final int[][] indexPos;     // occurrences per indexWords entry
        final long contextGeneration;

        State(String[] vocab, long vocabRevision, String[] tokens, String[] indexWords,
              int[][] indexPos, long contextGeneration) {
            this.vocab = vocab;
            this.vocabRevision = vocabRevision;
            this.tokens = tokens;
            this.indexWords = indexWords;
            this.indexPos = indexPos;
            this.contextGeneration = contextGeneration;
        }
    }

    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrences;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(State s) {
            this.vocabulary = s.vocab.clone();
            this.tokens = s.tokens.clone();
            this.occurrenceWords = s.indexWords.clone();
            this.occurrences = copy2(s.indexPos);
            this.vocabularyRevision = s.vocabRevision;
            this.contextGeneration = s.contextGeneration;
        }

        public String[] vocabulary() { return vocabulary.clone(); }
        public String[] tokens() { return tokens.clone(); }
        public String[] occurrenceWords() { return occurrenceWords.clone(); }
        public int[][] occurrences() { return copy2(occurrences); }
        public long vocabularyRevision() { return vocabularyRevision; }
        public long contextGeneration() { return contextGeneration; }
    }

    public static final class Session {
        private final State state;

        private Session(State state) {
            this.state = state;
        }

        public String[] findMatches(String prefix) {
            String p = normalize(prefix, true);
            String[] words = state.indexWords;
            String[] tmp = new String[words.length];
            int n = 0;
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                if (w.startsWith(p) && contains(state.vocab, w)) {
                    tmp[n++] = w;
                }
            }
            String[] res = new String[n];
            System.arraycopy(tmp, 0, res, 0, n);
            sortStrings(res, true);
            return res;
        }

        public int[] dependencies(int tokenIndex) {
            String[] tokens = state.tokens;
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("invalid token index");
            }
            int[] tmp = new int[tokens.length - tokenIndex - 1];
            int n = 0;
            for (int j = tokenIndex + 1; j < tokens.length; j++) {
                if (contains(state.vocab, tokens[j])) {
                    tmp[n++] = j;
                }
            }
            int[] res = new int[n];
            System.arraycopy(tmp, 0, res, 0, n);
            return res;
        }

        public Snapshot snapshot() {
            return new Snapshot(state);
        }
    }

    private final Object lock = new Object();
    private State state = new State(new String[0], 0L, new String[0], new String[0], new int[0][], 0L);

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("null words");
        }
        String[] in = words.clone();
        String[] norm = new String[in.length];
        for (int i = 0; i < in.length; i++) {
            norm[i] = normalize(in[i], false);
        }
        sortStrings(norm, false);
        int n = 0;
        String[] tmp = new String[norm.length];
        for (int i = 0; i < norm.length; i++) {
            if (n == 0 || !tmp[n - 1].equals(norm[i])) {
                tmp[n++] = norm[i];
            }
        }
        String[] vocab = new String[n];
        System.arraycopy(tmp, 0, vocab, 0, n);

        synchronized (lock) {
            State cur = state;
            if (cur.vocabRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            state = new State(vocab, cur.vocabRevision + 1, cur.tokens, cur.indexWords,
                    cur.indexPos, cur.contextGeneration);
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("null sentence");
        }
        int len = sentence.length();
        String[] buf = new String[8];
        int count = 0;
        int i = 0;
        while (i < len) {
            if (isLetter(sentence.charAt(i))) {
                int start = i;
                while (i < len && isLetter(sentence.charAt(i))) {
                    i++;
                }
                char[] cs = new char[i - start];
                for (int k = start; k < i; k++) {
                    cs[k - start] = lower(sentence.charAt(k));
                }
                if (count == buf.length) {
                    String[] nb = new String[buf.length * 2];
                    System.arraycopy(buf, 0, nb, 0, count);
                    buf = nb;
                }
                buf[count++] = new String(cs);
            } else {
                i++;
            }
        }
        String[] tokens = new String[count];
        System.arraycopy(buf, 0, tokens, 0, count);

        // Build index: sort token positions by (token, position)
        int[] order = new int[count];
        for (int k = 0; k < count; k++) order[k] = k;
        sortOrder(order, tokens);
        int distinct = 0;
        for (int k = 0; k < count; k++) {
            if (k == 0 || !tokens[order[k]].equals(tokens[order[k - 1]])) distinct++;
        }
        String[] iw = new String[distinct];
        int[][] ip = new int[distinct][];
        int g = -1;
        int k = 0;
        while (k < count) {
            int e = k;
            while (e < count && tokens[order[e]].equals(tokens[order[k]])) e++;
            g++;
            iw[g] = tokens[order[k]];
            int[] pos = new int[e - k];
            for (int m = k; m < e; m++) pos[m - k] = order[m];
            ip[g] = pos;
            k = e;
        }

        synchronized (lock) {
            State cur = state;
            if (cur.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            state = new State(cur.vocab, cur.vocabRevision, tokens, iw, ip,
                    cur.contextGeneration + 1);
        }
    }

    public Session openSession() {
        State s;
        synchronized (lock) {
            s = state;
        }
        return new Session(s);
    }

    public Snapshot snapshot() {
        State s;
        synchronized (lock) {
            s = state;
        }
        return new Snapshot(s);
    }

    // ---------- helpers ----------

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static char lower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    private static String normalize(String s, boolean allowEmpty) {
        if (s == null) {
            throw new IllegalArgumentException("null string");
        }
        int n = s.length();
        if (n == 0 && !allowEmpty) {
            throw new IllegalArgumentException("empty word");
        }
        char[] cs = new char[n];
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (!isLetter(c)) {
                throw new IllegalArgumentException("invalid character");
            }
            cs[i] = lower(c);
        }
        return new String(cs);
    }

    private static boolean contains(String[] sorted, String w) {
        int lo = 0, hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = sorted[mid].compareTo(w);
            if (c == 0) return true;
            if (c < 0) lo = mid + 1; else hi = mid - 1;
        }
        return false;
    }

    private static int cmp(String a, String b, boolean lengthFirst) {
        if (lengthFirst) {
            if (a.length() != b.length()) {
                return a.length() > b.length() ? -1 : 1;
            }
        }
        return a.compareTo(b);
    }

    private static void sortStrings(String[] a, boolean lengthFirst) {
        if (a.length < 2) return;
        String[] src = a;
        String[] dst = new String[a.length];
        for (int width = 1; width < a.length; width *= 2) {
            for (int lo = 0; lo < a.length; lo += 2 * width) {
                int mid = Math.min(lo + width, a.length);
                int hi = Math.min(lo + 2 * width, a.length);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    if (cmp(src[j], src[i], lengthFirst) < 0) dst[k++] = src[j++];
                    else dst[k++] = src[i++];
                }
                while (i < mid) dst[k++] = src[i++];
                while (j < hi) dst[k++] = src[j++];
            }
            String[] t = src; src = dst; dst = t;
        }
        if (src != a) System.arraycopy(src, 0, a, 0, a.length);
    }

    private static void sortOrder(int[] a, String[] tokens) {
        if (a.length < 2) return;
        int[] src = a;
        int[] dst = new int[a.length];
        for (int width = 1; width < a.length; width *= 2) {
            for (int lo = 0; lo < a.length; lo += 2 * width) {
                int mid = Math.min(lo + width, a.length);
                int hi = Math.min(lo + 2 * width, a.length);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    int c = tokens[src[j]].compareTo(tokens[src[i]]);
                    if (c < 0 || (c == 0 && src[j] < src[i])) dst[k++] = src[j++];
                    else dst[k++] = src[i++];
                }
                while (i < mid) dst[k++] = src[i++];
                while (j < hi) dst[k++] = src[j++];
            }
            int[] t = src; src = dst; dst = t;
        }
        if (src != a) System.arraycopy(src, 0, a, 0, a.length);
    }

    private static int[][] copy2(int[][] a) {
        int[][] r = new int[a.length][];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i].clone();
        }
        return r;
    }
}
