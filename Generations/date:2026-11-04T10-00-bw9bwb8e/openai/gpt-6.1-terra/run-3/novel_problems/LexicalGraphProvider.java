/**
 * A thread-safe provider of immutable vocabulary/context sessions.
 *
 * Operations have no capacity limit and do not wait for data to become
 * available. They may briefly block while acquiring the state monitor.
 * Revision or generation overflow causes IllegalStateException without
 * publishing the prepared replacement.
 */
@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private Vocabulary vocabulary = new Vocabulary(new String[0]);
    private Context context = new Context(
            new String[0], new String[0], new int[0][]);
    private long vocabularyRevision;
    private long contextGeneration;

    public LexicalGraphProvider() {
    }

    /**
     * Validates, copies, normalizes, and deduplicates the supplied words.
     * A successful call advances the vocabulary revision, even if the
     * resulting vocabulary is unchanged.
     */
    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }

        String[] input = words.clone();
        String[] normalized = new String[input.length];
        for (int i = 0; i < input.length; i++) {
            normalized[i] = normalizeLetters(input[i], false);
        }

        sortStrings(normalized, false);
        int uniqueCount = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                uniqueCount++;
            }
        }

        String[] unique = new String[uniqueCount];
        int output = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                unique[output++] = normalized[i];
            }
        }
        Vocabulary prepared = new Vocabulary(unique);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    /**
     * Extracts maximal ASCII-letter runs and publishes the resulting context.
     * A successful call advances the context generation, even if the
     * resulting tokens are unchanged.
     */
    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence must not be null");
        }

        Context prepared = prepareContext(sentence);
        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            context = prepared;
            contextGeneration++;
        }
    }

    /** Captures both components and their versions in one atomic operation. */
    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                    vocabulary, context, vocabularyRevision, contextGeneration);
        }
    }

    /** Returns a detached snapshot of one atomically captured state. */
    public Snapshot snapshot() {
        return openSession().snapshot();
    }

    /**
     * An immutable view of the vocabulary and context captured when opened.
     * Its operations do not acquire the provider's state monitor.
     */
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

        /**
         * Returns distinct matching vocabulary words that occur in this
         * context, ordered by decreasing length and then ascending spelling.
         */
        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizeLetters(prefix, true);
            String[] words = vocabulary.words;
            String[] temporary = new String[words.length];
            int count = 0;

            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                if (word.startsWith(normalizedPrefix)
                        && binarySearch(context.occurrenceWords, word) >= 0) {
                    temporary[count++] = word;
                }
            }

            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                result[i] = temporary[i];
            }
            sortStrings(result, true);
            return result;
        }

        /**
         * Returns all later token indexes whose tokens belong to the pinned
         * vocabulary, in increasing index order.
         */
        public int[] dependencies(int tokenIndex) {
            String[] tokens = context.tokens;
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary.words, tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int output = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary.words, tokens[i]) >= 0) {
                    result[output++] = i;
                }
            }
            return result;
        }

        /** Returns a detached snapshot of this session's pinned state. */
        public Snapshot snapshot() {
            return new Snapshot(vocabulary, context,
                    vocabularyRevision, contextGeneration);
        }
    }

    /**
     * A detached state snapshot. Array getters return fresh copies.
     * Occurrence words are sorted lexically; each corresponding indexes
     * array contains all positions of that word in increasing order.
     */
    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(Vocabulary vocabulary, Context context,
                         long vocabularyRevision, long contextGeneration) {
            this.vocabulary = vocabulary.words.clone();
            this.tokens = context.tokens.clone();
            this.occurrenceWords = context.occurrenceWords.clone();
            this.occurrenceIndexes = copyIndexes(context.occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] getVocabulary() {
            return vocabulary.clone();
        }

        public String[] getTokens() {
            return tokens.clone();
        }

        public String[] getOccurrenceWords() {
            return occurrenceWords.clone();
        }

        public int[][] getOccurrenceIndexes() {
            return copyIndexes(occurrenceIndexes);
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
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
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;

        private Context(String[] tokens, String[] occurrenceWords,
                        int[][] occurrenceIndexes) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
        }
    }

    private static int[][] copyIndexes(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char lowercaseAscii(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + ('a' - 'A'));
        }
        return c;
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("invalid letter string");
        }

        char[] characters = new char[value.length()];
        for (int i = 0; i < characters.length; i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("invalid letter string");
            }
            characters[i] = lowercaseAscii(c);
        }
        return new String(characters);
    }

    private static Context prepareContext(String sentence) {
        int length = sentence.length();
        int tokenCount = 0;
        int position = 0;

        while (position < length) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
            } else {
                tokenCount++;
                do {
                    position++;
                } while (position < length
                        && isAsciiLetter(sentence.charAt(position)));
            }
        }

        String[] tokens = new String[tokenCount];
        position = 0;
        int tokenIndex = 0;
        while (position < length) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
                continue;
            }

            int start = position;
            do {
                position++;
            } while (position < length
                    && isAsciiLetter(sentence.charAt(position)));

            char[] characters = new char[position - start];
            for (int i = 0; i < characters.length; i++) {
                characters[i] = lowercaseAscii(sentence.charAt(start + i));
            }
            tokens[tokenIndex++] = new String(characters);
        }

        int[] positions = new int[tokenCount];
        for (int i = 0; i < tokenCount; i++) {
            positions[i] = i;
        }
        if (tokenCount > 1) {
            int[] scratch = new int[tokenCount];
            sortPositions(positions, scratch, tokens, 0, tokenCount);
        }

        int distinctCount = 0;
        for (int i = 0; i < tokenCount; i++) {
            if (i == 0 || !tokens[positions[i]].equals(tokens[positions[i - 1]])) {
                distinctCount++;
            }
        }

        String[] occurrenceWords = new String[distinctCount];
        int[][] occurrenceIndexes = new int[distinctCount][];
        int group = 0;
        int start = 0;
        while (start < tokenCount) {
            String word = tokens[positions[start]];
            int end = start + 1;
            while (end < tokenCount && word.equals(tokens[positions[end]])) {
                end++;
            }

            occurrenceWords[group] = word;
            int[] indexes = new int[end - start];
            for (int i = start; i < end; i++) {
                indexes[i - start] = positions[i];
            }
            occurrenceIndexes[group] = indexes;
            group++;
            start = end;
        }

        return new Context(tokens, occurrenceWords, occurrenceIndexes);
    }

    /**
     * Stable sort by token spelling. Since positions initially increase,
     * positions belonging to the same word remain in increasing order.
     */
    private static void sortPositions(int[] positions, int[] scratch,
                                      String[] tokens, int from, int to) {
        if (to - from <= 1) {
            return;
        }
        int middle = from + (to - from) / 2;
        sortPositions(positions, scratch, tokens, from, middle);
        sortPositions(positions, scratch, tokens, middle, to);

        int left = from;
        int right = middle;
        int output = from;
        while (left < middle && right < to) {
            if (tokens[positions[left]].compareTo(tokens[positions[right]]) <= 0) {
                scratch[output++] = positions[left++];
            } else {
                scratch[output++] = positions[right++];
            }
        }
        while (left < middle) {
            scratch[output++] = positions[left++];
        }
        while (right < to) {
            scratch[output++] = positions[right++];
        }
        for (int i = from; i < to; i++) {
            positions[i] = scratch[i];
        }
    }

    private static int binarySearch(String[] sorted, String target) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sorted[middle].compareTo(target);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    private static void sortStrings(String[] values, boolean matchOrder) {
        if (values.length > 1) {
            String[] scratch = new String[values.length];
            sortStrings(values, scratch, 0, values.length, matchOrder);
        }
    }

    private static void sortStrings(String[] values, String[] scratch,
                                    int from, int to, boolean matchOrder) {
        if (to - from <= 1) {
            return;
        }
        int middle = from + (to - from) / 2;
        sortStrings(values, scratch, from, middle, matchOrder);
        sortStrings(values, scratch, middle, to, matchOrder);

        int left = from;
        int right = middle;
        int output = from;
        while (left < middle && right < to) {
            if (compareStrings(values[left], values[right], matchOrder) <= 0) {
                scratch[output++] = values[left++];
            } else {
                scratch[output++] = values[right++];
            }
        }
        while (left < middle) {
            scratch[output++] = values[left++];
        }
        while (right < to) {
            scratch[output++] = values[right++];
        }
        for (int i = from; i < to; i++) {
            values[i] = scratch[i];
        }
    }

    private static int compareStrings(String first, String second,
                                      boolean matchOrder) {
        if (matchOrder && first.length() != second.length()) {
            return first.length() > second.length() ? -1 : 1;
        }
        return first.compareTo(second);
    }
}
