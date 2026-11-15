import java.lang.*;

@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}


    private static final class Vocab {
        final String[] words; // sorted, distinct, lowercase; never exposed
        final long revision;

        Vocab(String[] words, long revision) {
            this.words = words;
            this.revision = revision;
        }
    }

    private static final class Context {
        final String[] tokens;      // in order
        final String[] distinct;    // sorted distinct tokens
        final int[][] positions;    // positions[i] = increasing indexes of distinct[i]
        final long generation;

        Context(String[] tokens, String[] distinct, int[][] positions, long generation) {
            this.tokens = tokens;
            this.distinct = distinct;
            this.positions = positions;
            this.generation = generation;
        }
    }

    // Prepared (not yet versioned) context data
    private static final class PreparedContext {
        final String[] tokens;
        final String[] distinct;
        final int[][] positions;

        PreparedContext(String[] tokens, String[] distinct, int[][] positions) {
            this.tokens = tokens;
            this.distinct = distinct;
            this.positions = positions;
        }
    }

    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(Vocab v, Context c) {
            this.vocabulary = v.words.clone();
            this.tokens = c.tokens.clone();
            this.occurrenceWords = c.distinct.clone();
            this.occurrenceIndexes = new int[c.positions.length][];
            for (int i = 0; i < c.positions.length; i++) {
                this.occurrenceIndexes[i] = c.positions[i].clone();
            }
            this.vocabularyRevision = v.revision;
            this.contextGeneration = c.generation;
        }

        public String[] vocabulary() {
            return vocabulary.clone();
        }

        public String[] tokens() {
            return tokens.clone();
        }

        public String[] occurrenceWords() {
            return occurrenceWords.clone();
        }

        public int[][] occurrenceIndexes() {
            int[][] r = new int[occurrenceIndexes.length][];
            for (int i = 0; i < r.length; i++) {
                r[i] = occurrenceIndexes[i].clone();
            }
            return r;
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public long contextGeneration() {
            return contextGeneration;
        }

        public String[] getVocabulary() {
            return vocabulary();
        }

        public String[] getTokens() {
            return tokens();
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }
    }

    public static final class Session {
        private final Vocab vocab;
        private final Context context;

        private Session(Vocab v, Context c) {
            this.vocab = v;
            this.context = c;
        }

        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix is null");
            }
            String p = normalizeLetters(prefix, true);
            String[] d = context.distinct;
            String[] tmp = new String[d.length];
            int n = 0;
            for (int i = 0; i < d.length; i++) {
                String w = d[i];
                if (w.startsWith(p) && contains(vocab.words, w)) {
                    tmp[n++] = w;
                }
            }
            String[] res = new String[n];
            System.arraycopy(tmp, 0, res, 0, n);
            sort(res, true);
            return res;
        }

        public int[] dependencies(int tokenIndex) {
            String[] toks = context.tokens;
            if (tokenIndex < 0 || tokenIndex >= toks.length) {
                throw new IllegalArgumentException("invalid token index: " + tokenIndex);
            }
            int[] tmp = new int[toks.length - tokenIndex - 1];
            int n = 0;
            for (int j = tokenIndex + 1; j < toks.length; j++) {
                if (contains(vocab.words, toks[j])) {
                    tmp[n++] = j;
                }
            }
            int[] res = new int[n];
            System.arraycopy(tmp, 0, res, 0, n);
            return res;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocab, context);
        }
    }

    private final Object stateMonitor = new Object();
    private Vocab currentVocab = new Vocab(new String[0], 0L);
    private Context currentContext =
            new Context(new String[0], new String[0], new int[0][], 0L);

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words is null");
        }
        String[] copy = words.clone();
        String[] norm = new String[copy.length];
        for (int i = 0; i < copy.length; i++) {
            String w = copy[i];
            if (w == null) {
                throw new IllegalArgumentException("null word");
            }
            if (w.length() == 0) {
                throw new IllegalArgumentException("empty word");
            }
            norm[i] = normalizeLetters(w, false);
        }
        sort(norm, false);
        int n = 0;
        for (int i = 0; i < norm.length; i++) {
            if (n == 0 || !norm[n - 1].equals(norm[i])) {
                norm[n++] = norm[i];
            }
        }
        String[] finalWords = new String[n];
        System.arraycopy(norm, 0, finalWords, 0, n);

        synchronized (stateMonitor) {
            long rev = currentVocab.revision;
            if (rev == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            currentVocab = new Vocab(finalWords, rev + 1);
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence is null");
        }
        PreparedContext pc = prepareContext(sentence);
        synchronized (stateMonitor) {
            long gen = currentContext.generation;
            if (gen == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            currentContext = new Context(pc.tokens, pc.distinct, pc.positions, gen + 1);
        }
    }

    public Session openSession() {
        Vocab v;
        Context c;
        synchronized (stateMonitor) {
            v = currentVocab;
            c = currentContext;
        }
        return new Session(v, c);
    }

    public Snapshot snapshot() {
        Vocab v;
        Context c;
        synchronized (stateMonitor) {
            v = currentVocab;
            c = currentContext;
        }
        return new Snapshot(v, c);
    }

    // ---------- helpers ----------

    private static boolean isLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static String normalizeLetters(String s, boolean allowEmpty) {
        int len = s.length();
        if (len == 0 && !allowEmpty) {
            throw new IllegalArgumentException("empty word");
        }
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                out[i] = (char) (ch + ('a' - 'A'));
            } else if (ch >= 'a' && ch <= 'z') {
                out[i] = ch;
            } else {
                throw new IllegalArgumentException("invalid character in word");
            }
        }
        return new String(out);
    }

    private static PreparedContext prepareContext(String s) {
        int len = s.length();
        int count = 0;
        int i = 0;
        while (i < len) {
            if (isLetter(s.charAt(i))) {
                count++;
                while (i < len && isLetter(s.charAt(i))) {
                    i++;
                }
            } else {
                i++;
            }
        }
        String[] tokens = new String[count];
        int t = 0;
        i = 0;
        while (i < len) {
            if (isLetter(s.charAt(i))) {
                int start = i;
                while (i < len && isLetter(s.charAt(i))) {
                    i++;
                }
                char[] buf = new char[i - start];
                for (int k = start; k < i; k++) {
                    char ch = s.charAt(k);
                    if (ch >= 'A' && ch <= 'Z') {
                        ch = (char) (ch + ('a' - 'A'));
                    }
                    buf[k - start] = ch;
                }
                tokens[t++] = new String(buf);
            } else {
                i++;
            }
        }
        String[] sorted = tokens.clone();
        sort(sorted, false);
        int n = 0;
        for (int k = 0; k < sorted.length; k++) {
            if (n == 0 || !sorted[n - 1].equals(sorted[k])) {
                sorted[n++] = sorted[k];
            }
        }
        String[] distinct = new String[n];
        System.arraycopy(sorted, 0, distinct, 0, n);
        int[] counts = new int[n];
        int[] ids = new int[count];
        for (int k = 0; k < count; k++) {
            int id = indexOf(distinct, tokens[k]);
            ids[k] = id;
            counts[id]++;
        }
        int[][] positions = new int[n][];
        for (int k = 0; k < n; k++) {
            positions[k] = new int[counts[k]];
        }
        int[] fill = new int[n];
        for (int k = 0; k < count; k++) {
            int id = ids[k];
            positions[id][fill[id]++] = k;
        }
        return new PreparedContext(tokens, distinct, positions);
    }

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

    private static boolean contains(String[] sorted, String key) {
        return indexOf(sorted, key) >= 0;
    }

    private static int cmp(String a, String b, boolean lengthFirst) {
        if (lengthFirst) {
            int la = a.length();
            int lb = b.length();
            if (la != lb) {
                return la > lb ? -1 : 1;
            }
        }
        return a.compareTo(b);
    }

    private static void sort(String[] a, boolean lengthFirst) {
        if (a.length < 2) {
            return;
        }
        String[] tmp = new String[a.length];
        mergeSort(a, tmp, 0, a.length, lengthFirst);
    }

    private static void mergeSort(String[] a, String[] tmp, int lo, int hi, boolean lf) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid, lf);
        mergeSort(a, tmp, mid, hi, lf);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (cmp(a[j], a[i], lf) < 0) {
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
        for (k = lo; k < hi; k++) {
            a[k] = tmp[k];
        }
    }
}
