@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}


    private final Object lock = new Object();

    // Guarded by lock. The referenced data objects are immutable once published.
    private VocabData vocabData = new VocabData(new String[0]);
    private ContextData contextData = buildContext("");
    private long vocabRevision = 0L;
    private long contextGeneration = 0L;

    public LexicalGraphProvider() {
    }

    // ---------- immutable internal data ----------

    private static final class VocabData {
        final String[] words; // sorted, distinct, lowercase

        VocabData(String[] words) {
            this.words = words;
        }
    }

    private static final class ContextData {
        final String[] tokens;       // in order
        final String[] distinct;     // sorted distinct tokens
        final int[][] occurrences;   // occurrences[i] = increasing indexes of distinct[i]

        ContextData(String[] tokens, String[] distinct, int[][] occurrences) {
            this.tokens = tokens;
            this.distinct = distinct;
            this.occurrences = occurrences;
        }
    }

    // ---------- public snapshot type ----------

    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(VocabData v, ContextData c, long vr, long cg) {
            this.vocabulary = v.words.clone();
            this.tokens = c.tokens.clone();
            this.occurrenceWords = c.distinct.clone();
            this.occurrenceIndexes = new int[c.occurrences.length][];
            for (int i = 0; i < c.occurrences.length; i++) {
                this.occurrenceIndexes[i] = c.occurrences[i].clone();
            }
            this.vocabularyRevision = vr;
            this.contextGeneration = cg;
        }

        public String[] getVocabulary() {
            return vocabulary.clone();
        }

        public String[] getTokens() {
            return tokens.clone();
        }

        /** Distinct context words, parallel to getOccurrenceIndexes(). */
        public String[] getOccurrenceWords() {
            return occurrenceWords.clone();
        }

        public int[][] getOccurrenceIndexes() {
            int[][] r = new int[occurrenceIndexes.length][];
            for (int i = 0; i < r.length; i++) {
                r[i] = occurrenceIndexes[i].clone();
            }
            return r;
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }
    }

    // ---------- session ----------

    public static final class Session {
        private final VocabData vocab;
        private final ContextData context;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(VocabData v, ContextData c, long vr, long cg) {
            this.vocab = v;
            this.context = c;
            this.vocabularyRevision = vr;
            this.contextGeneration = cg;
        }

        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix is null");
            }
            if (!isAsciiLetters(prefix)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            String p = lower(prefix);
            String[] tmp = new String[vocab.words.length];
            int n = 0;
            for (int i = 0; i < vocab.words.length; i++) {
                String w = vocab.words[i];
                if (w.startsWith(p) && binarySearch(context.distinct, w) >= 0) {
                    tmp[n++] = w;
                }
            }
            String[] res = new String[n];
            System.arraycopy(tmp, 0, res, 0, n);
            mergeSortStrings(res, true);
            return res;
        }

        public int[] dependencies(int tokenIndex) {
            String[] toks = context.tokens;
            if (tokenIndex < 0 || tokenIndex >= toks.length) {
                throw new IllegalArgumentException("invalid token index");
            }
            int[] tmp = new int[toks.length - tokenIndex - 1];
            int n = 0;
            for (int i = tokenIndex + 1; i < toks.length; i++) {
                if (binarySearch(vocab.words, toks[i]) >= 0) {
                    tmp[n++] = i;
                }
            }
            int[] res = new int[n];
            System.arraycopy(tmp, 0, res, 0, n);
            return res;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocab, context, vocabularyRevision, contextGeneration);
        }
    }

    // ---------- provider operations ----------

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words is null");
        }
        String[] copy = words.clone();
        String[] norm = new String[copy.length];
        for (int i = 0; i < copy.length; i++) {
            String w = copy[i];
            if (w == null || w.length() == 0 || !isAsciiLetters(w)) {
                throw new IllegalArgumentException("invalid word at index " + i);
            }
            norm[i] = lower(w);
        }
        mergeSortStrings(norm, false);
        int n = 0;
        String[] uniq = new String[norm.length];
        for (int i = 0; i < norm.length; i++) {
            if (n == 0 || !uniq[n - 1].equals(norm[i])) {
                uniq[n++] = norm[i];
            }
        }
        String[] fin = new String[n];
        System.arraycopy(uniq, 0, fin, 0, n);
        VocabData data = new VocabData(fin);

        synchronized (lock) {
            if (vocabRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            vocabData = data;
            vocabRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence is null");
        }
        ContextData data = buildContext(sentence);
        synchronized (lock) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            contextData = data;
            contextGeneration++;
        }
    }

    public Session openSession() {
        VocabData v;
        ContextData c;
        long vr;
        long cg;
        synchronized (lock) {
            v = vocabData;
            c = contextData;
            vr = vocabRevision;
            cg = contextGeneration;
        }
        return new Session(v, c, vr, cg);
    }

    public Snapshot snapshot() {
        VocabData v;
        ContextData c;
        long vr;
        long cg;
        synchronized (lock) {
            v = vocabData;
            c = contextData;
            vr = vocabRevision;
            cg = contextGeneration;
        }
        return new Snapshot(v, c, vr, cg);
    }

    // ---------- helpers ----------

    private static boolean isLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static boolean isAsciiLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String lower(String s) {
        char[] cs = new char[s.length()];
        for (int i = 0; i < cs.length; i++) {
            char ch = s.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                ch = (char) (ch + ('a' - 'A'));
            }
            cs[i] = ch;
        }
        return new String(cs);
    }

    private static int binarySearch(String[] sorted, String key) {
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

    // mode false: ascending lexical; true: decreasing length then lexical.
    private static boolean lessOrEqual(String a, String b, boolean byLength) {
        if (byLength) {
            if (a.length() != b.length()) {
                return a.length() > b.length();
            }
        }
        return a.compareTo(b) <= 0;
    }

    private static void mergeSortStrings(String[] a, boolean byLength) {
        if (a.length < 2) {
            return;
        }
        String[] buf = new String[a.length];
        msStrings(a, buf, 0, a.length, byLength);
    }

    private static void msStrings(String[] a, String[] buf, int lo, int hi, boolean byLength) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        msStrings(a, buf, lo, mid, byLength);
        msStrings(a, buf, mid, hi, byLength);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (lessOrEqual(a[i], a[j], byLength)) {
                buf[k++] = a[i++];
            } else {
                buf[k++] = a[j++];
            }
        }
        while (i < mid) {
            buf[k++] = a[i++];
        }
        while (j < hi) {
            buf[k++] = a[j++];
        }
        System.arraycopy(buf, lo, a, lo, hi - lo);
    }

    // Stable sort of index array by token text.
    private static void msIdx(int[] idx, int[] buf, String[] toks, int lo, int hi) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        msIdx(idx, buf, toks, lo, mid);
        msIdx(idx, buf, toks, mid, hi);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (toks[idx[i]].compareTo(toks[idx[j]]) <= 0) {
                buf[k++] = idx[i++];
            } else {
                buf[k++] = idx[j++];
            }
        }
        while (i < mid) {
            buf[k++] = idx[i++];
        }
        while (j < hi) {
            buf[k++] = idx[j++];
        }
        System.arraycopy(buf, lo, idx, lo, hi - lo);
    }

    private static ContextData buildContext(String sentence) {
        // count tokens
        int len = sentence.length();
        int count = 0;
        int i = 0;
        while (i < len) {
            if (isLetter(sentence.charAt(i))) {
                count++;
                while (i < len && isLetter(sentence.charAt(i))) {
                    i++;
                }
            } else {
                i++;
            }
        }
        String[] tokens = new String[count];
        int n = 0;
        i = 0;
        while (i < len) {
            if (isLetter(sentence.charAt(i))) {
                int start = i;
                while (i < len && isLetter(sentence.charAt(i))) {
                    i++;
                }
                tokens[n++] = lower(sentence.substring(start, i));
            } else {
                i++;
            }
        }
        int[] idx = new int[count];
        for (int k = 0; k < count; k++) {
            idx[k] = k;
        }
        if (count > 1) {
            msIdx(idx, new int[count], tokens, 0, count);
        }
        // group
        int groups = 0;
        for (int k = 0; k < count; k++) {
            if (k == 0 || !tokens[idx[k]].equals(tokens[idx[k - 1]])) {
                groups++;
            }
        }
        String[] distinct = new String[groups];
        int[][] occ = new int[groups][];
        int k = 0;
        int g = 0;
        while (k < count) {
            int e = k;
            while (e < count && tokens[idx[e]].equals(tokens[idx[k]])) {
                e++;
            }
            int[] o = new int[e - k];
            for (int m = k; m < e; m++) {
                o[m - k] = idx[m];
            }
            distinct[g] = tokens[idx[k]];
            occ[g] = o;
            g++;
            k = e;
        }
        return new ContextData(tokens, distinct, occ);
    }
}
