@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private Vocabulary vocabulary = new Vocabulary(new String[0]);
    private Context context = new Context(new String[0], new String[0], new int[0][]);
    private long vocabularyRevision;
    private long contextGeneration;

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }
        String[] prepared = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            prepared[i] = normalize(words[i], false);
        }
        sortWords(prepared, false);
        int uniqueCount = 0;
        for (int i = 0; i < prepared.length; i++) {
            if (uniqueCount == 0 || !prepared[i].equals(prepared[uniqueCount - 1])) {
                prepared[uniqueCount++] = prepared[i];
            }
        }
        String[] uniqueWords = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            uniqueWords[i] = prepared[i];
        }
        Vocabulary replacement = new Vocabulary(uniqueWords);
        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            vocabulary = replacement;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence must not be null");
        }
        Context replacement = buildContext(sentence);
        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            context = replacement;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(vocabulary, context, vocabularyRevision, contextGeneration);
        }
    }

    public Snapshot snapshot() {
        return openSession().snapshot();
    }

    public static final class Session {
        private final Vocabulary vocabulary;
        private final Context context;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(Vocabulary vocabulary, Context context,
                        long vocabularyRevision, long contextGeneration) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalized = normalize(prefix, true);
            String[] candidates = new String[vocabulary.words.length];
            int count = 0;
            for (int i = 0; i < vocabulary.words.length; i++) {
                String word = vocabulary.words[i];
                if (word.startsWith(normalized) && findWord(context.words, word) >= 0) {
                    candidates[count++] = word;
                }
            }
            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                result[i] = candidates[i];
            }
            sortWords(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("invalid token index");
            }
            int[] candidates = new int[context.tokens.length - tokenIndex - 1];
            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findWord(vocabulary.words, context.tokens[i]) >= 0) {
                    candidates[count++] = i;
                }
            }
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = candidates[i];
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocabulary.words, context.tokens, context.words,
                                context.occurrences, vocabularyRevision, contextGeneration);
        }
    }

    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(String[] vocabulary, String[] tokens, String[] occurrenceWords,
                         int[][] occurrenceIndexes, long vocabularyRevision,
                         long contextGeneration) {
            this.vocabulary = copyStrings(vocabulary);
            this.tokens = copyStrings(tokens);
            this.occurrenceWords = copyStrings(occurrenceWords);
            this.occurrenceIndexes = copyIndexes(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] vocabulary() {
            return copyStrings(vocabulary);
        }

        public String[] tokens() {
            return copyStrings(tokens);
        }

        public String[] occurrenceWords() {
            return copyStrings(occurrenceWords);
        }

        // The indexes at position i correspond to occurrenceWords()[i].
        public int[][] occurrenceIndexes() {
            return copyIndexes(occurrenceIndexes);
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public long contextGeneration() {
            return contextGeneration;
        }
    }

    private static final class Vocabulary {
        private final String[] words;

        private Vocabulary(String[] words) {
            this.words = words;
        }
    }

    private static final class Context {
        private final String[] tokens;
        private final String[] words;
        private final int[][] occurrences;

        private Context(String[] tokens, String[] words, int[][] occurrences) {
            this.tokens = tokens;
            this.words = words;
            this.occurrences = occurrences;
        }
    }

    private static String normalize(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("invalid word or prefix");
        }
        char[] chars = new char[value.length()];
        for (int i = 0; i < chars.length; i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("invalid word or prefix");
            }
            chars[i] = lower(c);
        }
        return new String(chars);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static Context buildContext(String sentence) {
        int tokenCount = 0;
        boolean inToken = false;
        for (int i = 0; i < sentence.length(); i++) {
            if (isAsciiLetter(sentence.charAt(i))) {
                if (!inToken) {
                    tokenCount++;
                    inToken = true;
                }
            } else {
                inToken = false;
            }
        }
        String[] tokens = new String[tokenCount];
        int tokenPosition = 0;
        int cursor = 0;
        while (cursor < sentence.length()) {
            if (!isAsciiLetter(sentence.charAt(cursor))) {
                cursor++;
                continue;
            }
            int start = cursor;
            while (cursor < sentence.length() && isAsciiLetter(sentence.charAt(cursor))) {
                cursor++;
            }
            char[] chars = new char[cursor - start];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = lower(sentence.charAt(start + i));
            }
            tokens[tokenPosition++] = new String(chars);
        }

        int[] order = new int[tokenCount];
        for (int i = 0; i < tokenCount; i++) {
            order[i] = i;
        }
        sortIndexes(order, new int[tokenCount], tokens, 0, tokenCount);
        int distinctCount = 0;
        for (int i = 0; i < tokenCount; i++) {
            if (i == 0 || !tokens[order[i]].equals(tokens[order[i - 1]])) {
                distinctCount++;
            }
        }
        String[] words = new String[distinctCount];
        int[][] occurrences = new int[distinctCount][];
        int group = 0;
        int start = 0;
        while (start < tokenCount) {
            int end = start + 1;
            while (end < tokenCount && tokens[order[start]].equals(tokens[order[end]])) {
                end++;
            }
            words[group] = tokens[order[start]];
            occurrences[group] = new int[end - start];
            for (int i = start; i < end; i++) {
                occurrences[group][i - start] = order[i];
            }
            group++;
            start = end;
        }
        return new Context(tokens, words, occurrences);
    }

    private static void sortIndexes(int[] order, int[] scratch, String[] tokens,
                                    int start, int end) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        sortIndexes(order, scratch, tokens, start, middle);
        sortIndexes(order, scratch, tokens, middle, end);
        int left = start;
        int right = middle;
        int out = start;
        while (left < middle && right < end) {
            int comparison = tokens[order[left]].compareTo(tokens[order[right]]);
            if (comparison < 0 || (comparison == 0 && order[left] < order[right])) {
                scratch[out++] = order[left++];
            } else {
                scratch[out++] = order[right++];
            }
        }
        while (left < middle) {
            scratch[out++] = order[left++];
        }
        while (right < end) {
            scratch[out++] = order[right++];
        }
        for (int i = start; i < end; i++) {
            order[i] = scratch[i];
        }
    }

    private static void sortWords(String[] words, boolean byLength) {
        sortWords(words, new String[words.length], 0, words.length, byLength);
    }

    private static void sortWords(String[] words, String[] scratch, int start, int end,
                                  boolean byLength) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        sortWords(words, scratch, start, middle, byLength);
        sortWords(words, scratch, middle, end, byLength);
        int left = start;
        int right = middle;
        int out = start;
        while (left < middle && right < end) {
            if (compareWords(words[left], words[right], byLength) <= 0) {
                scratch[out++] = words[left++];
            } else {
                scratch[out++] = words[right++];
            }
        }
        while (left < middle) {
            scratch[out++] = words[left++];
        }
        while (right < end) {
            scratch[out++] = words[right++];
        }
        for (int i = start; i < end; i++) {
            words[i] = scratch[i];
        }
    }

    private static int compareWords(String left, String right, boolean byLength) {
        if (byLength && left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return left.compareTo(right);
    }

    private static int findWord(String[] words, String word) {
        int low = 0;
        int high = words.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = words[middle].compareTo(word);
            if (comparison == 0) {
                return middle;
            }
            if (comparison < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return -1;
    }

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static int[][] copyIndexes(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = new int[source[i].length];
            for (int j = 0; j < source[i].length; j++) {
                copy[i][j] = source[i][j];
            }
        }
        return copy;
    }
}
