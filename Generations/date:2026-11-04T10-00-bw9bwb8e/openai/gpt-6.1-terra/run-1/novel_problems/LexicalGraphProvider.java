@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private Vocabulary vocabulary = new Vocabulary(new String[0]);
    private Context context = new Context(
            new String[0], new String[0], new int[0][]);
    private long vocabularyRevision;
    private long contextGeneration;

    /**
     * Validates and prepares the entire vocabulary before publishing it.
     * Invalid input throws IllegalArgumentException without changing the state.
     */
    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] input = words.clone();
        String[] normalized = new String[input.length];
        for (int i = 0; i < input.length; i++) {
            normalized[i] = normalizeLetters(input[i], false);
        }

        sortStrings(normalized, false);
        int distinct = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (distinct == 0 || !normalized[i].equals(normalized[distinct - 1])) {
                normalized[distinct++] = normalized[i];
            }
        }

        String[] preparedWords = new String[distinct];
        for (int i = 0; i < distinct; i++) {
            preparedWords[i] = normalized[i];
        }
        Vocabulary prepared = new Vocabulary(preparedWords);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    /**
     * Tokenizes and indexes the sentence before publishing the new context.
     */
    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }
        Context prepared = prepareContext(sentence);

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            context = prepared;
            contextGeneration++;
        }
    }

    /**
     * Captures both components and their versions in one atomic operation.
     */
    public Session openSession() {
        Vocabulary capturedVocabulary;
        Context capturedContext;
        long capturedRevision;
        long capturedGeneration;

        synchronized (stateMonitor) {
            capturedVocabulary = vocabulary;
            capturedContext = context;
            capturedRevision = vocabularyRevision;
            capturedGeneration = contextGeneration;
        }

        return new Session(capturedVocabulary, capturedContext,
                capturedRevision, capturedGeneration);
    }

    public Snapshot snapshot() {
        return openSession().snapshot();
    }

    /**
     * A session is pinned to the provider state captured when it was opened.
     * Query result arrays are newly allocated for each call.
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

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizeLetters(prefix, true);
            String[] candidates = new String[vocabulary.words.length];
            int count = 0;

            for (int i = 0; i < vocabulary.words.length; i++) {
                String word = vocabulary.words[i];
                if (word.startsWith(normalizedPrefix)
                        && binarySearch(context.occurrenceWords, word) >= 0) {
                    candidates[count++] = word;
                }
            }

            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                result[i] = candidates[i];
            }
            sortStrings(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (binarySearch(vocabulary.words, context.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (binarySearch(vocabulary.words, context.tokens[i]) >= 0) {
                    result[next++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(vocabulary, context,
                    vocabularyRevision, contextGeneration);
        }
    }

    /**
     * Detached snapshot. Occurrence words are sorted lexically, and each row
     * of occurrence indexes corresponds to the word at the same position.
     * Array getters return fresh copies.
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

    private static Context prepareContext(String sentence) {
        int tokenCount = 0;
        int position = 0;

        while (position < sentence.length()) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
            } else {
                tokenCount++;
                while (position < sentence.length()
                        && isAsciiLetter(sentence.charAt(position))) {
                    position++;
                }
            }
        }

        String[] tokens = new String[tokenCount];
        position = 0;
        int tokenIndex = 0;
        while (position < sentence.length()) {
            if (!isAsciiLetter(sentence.charAt(position))) {
                position++;
                continue;
            }

            int start = position;
            while (position < sentence.length()
                    && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }
            char[] letters = new char[position - start];
            for (int i = 0; i < letters.length; i++) {
                letters[i] = lowerAscii(sentence.charAt(start + i));
            }
            tokens[tokenIndex++] = new String(letters);
        }

        String[] sorted = tokens.clone();
        sortStrings(sorted, false);
        int distinct = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (distinct == 0 || !sorted[i].equals(sorted[distinct - 1])) {
                sorted[distinct++] = sorted[i];
            }
        }

        String[] occurrenceWords = new String[distinct];
        for (int i = 0; i < distinct; i++) {
            occurrenceWords[i] = sorted[i];
        }

        int[] counts = new int[distinct];
        for (int i = 0; i < tokens.length; i++) {
            counts[binarySearch(occurrenceWords, tokens[i])]++;
        }

        int[][] indexes = new int[distinct][];
        for (int i = 0; i < distinct; i++) {
            indexes[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(occurrenceWords, tokens[i]);
            indexes[wordIndex][counts[wordIndex]++] = i;
        }

        return new Context(tokens, occurrenceWords, indexes);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("Invalid letter string");
        }

        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Only ASCII letters are allowed");
            }
            normalized[i] = lowerAscii(c);
        }
        return new String(normalized);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char lowerAscii(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + ('a' - 'A'));
        }
        return c;
    }

    private static int binarySearch(String[] sorted, String value) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sorted[middle].compareTo(value);
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

    private static int[][] copyIndexes(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    private static void sortStrings(String[] values, boolean lengthFirst) {
        if (values.length < 2) {
            return;
        }
        String[] temporary = new String[values.length];
        mergeSort(values, temporary, 0, values.length, lengthFirst);
    }

    private static void mergeSort(String[] values, String[] temporary,
                                  int start, int end, boolean lengthFirst) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        mergeSort(values, temporary, start, middle, lengthFirst);
        mergeSort(values, temporary, middle, end, lengthFirst);

        int left = start;
        int right = middle;
        int output = start;
        while (left < middle && right < end) {
            if (compareStrings(values[left], values[right], lengthFirst) <= 0) {
                temporary[output++] = values[left++];
            } else {
                temporary[output++] = values[right++];
            }
        }
        while (left < middle) {
            temporary[output++] = values[left++];
        }
        while (right < end) {
            temporary[output++] = values[right++];
        }
        for (int i = start; i < end; i++) {
            values[i] = temporary[i];
        }
    }

    private static int compareStrings(String first, String second,
                                      boolean lengthFirst) {
        if (lengthFirst && first.length() != second.length()) {
            return first.length() > second.length() ? -1 : 1;
        }
        return first.compareTo(second);
    }
}
