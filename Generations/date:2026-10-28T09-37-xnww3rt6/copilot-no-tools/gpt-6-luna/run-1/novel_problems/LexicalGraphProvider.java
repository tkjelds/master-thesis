@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private State state = new State(
            new String[0],
            new String[0],
            new String[0],
            new int[0][],
            0L,
            0L
    );

    public void replaceVocabulary(String[] words) {
        String[] vocabulary = prepareVocabulary(words);

        synchronized (stateMonitor) {
            if (state.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }

            State current = state;
            state = new State(
                    vocabulary,
                    current.tokens,
                    current.occurrenceWords,
                    current.occurrenceIndexes,
                    current.vocabularyRevision + 1L,
                    current.contextGeneration
            );
        }
    }

    public void setContext(String sentence) {
        String[] tokens = tokenize(sentence);
        Occurrences occurrences = buildOccurrences(tokens);

        synchronized (stateMonitor) {
            if (state.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }

            State current = state;
            state = new State(
                    current.vocabulary,
                    tokens,
                    occurrences.words,
                    occurrences.indexes,
                    current.vocabularyRevision,
                    current.contextGeneration + 1L
            );
        }
    }

    public Session openSession() {
        State captured;
        synchronized (stateMonitor) {
            captured = state;
        }

        return new Session(
                captured.vocabulary,
                captured.tokens,
                captured.occurrenceWords,
                captured.occurrenceIndexes,
                captured.vocabularyRevision,
                captured.contextGeneration
        );
    }

    public Snapshot snapshot() {
        State captured;
        synchronized (stateMonitor) {
            captured = state;
        }

        return new Snapshot(
                captured.vocabulary,
                captured.tokens,
                captured.occurrenceWords,
                captured.occurrenceIndexes,
                captured.vocabularyRevision,
                captured.contextGeneration
        );
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(
                String[] vocabulary,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                long vocabularyRevision,
                long contextGeneration
        ) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizePrefix(prefix);
            String[] matches = new String[vocabulary.length];
            int matchCount = 0;

            for (String word : vocabulary) {
                if (word.startsWith(normalizedPrefix)
                        && findWord(occurrenceWords, word) >= 0) {
                    int insertionPoint = 0;
                    while (insertionPoint < matchCount
                            && compareMatches(matches[insertionPoint], word) <= 0) {
                        insertionPoint++;
                    }

                    for (int i = matchCount; i > insertionPoint; i--) {
                        matches[i] = matches[i - 1];
                    }
                    matches[insertionPoint] = word;
                    matchCount++;
                }
            }

            String[] result = new String[matchCount];
            for (int i = 0; i < matchCount; i++) {
                result[i] = matches[i];
            }
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (findWord(vocabulary, tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int resultIndex = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (findWord(vocabulary, tokens[i]) >= 0) {
                    result[resultIndex++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary,
                    tokens,
                    occurrenceWords,
                    occurrenceIndexes,
                    vocabularyRevision,
                    contextGeneration
            );
        }
    }

    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(
                String[] vocabulary,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                long vocabularyRevision,
                long contextGeneration
        ) {
            this.vocabulary = copyStrings(vocabulary);
            this.tokens = copyStrings(tokens);
            this.occurrenceWords = copyStrings(occurrenceWords);
            this.occurrenceIndexes = copyIndexes(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] getVocabulary() {
            return copyStrings(vocabulary);
        }

        public String[] getTokens() {
            return copyStrings(tokens);
        }

        public String[] getOccurrenceWords() {
            return copyStrings(occurrenceWords);
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

    private static final class State {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private State(
                String[] vocabulary,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                long vocabularyRevision,
                long contextGeneration
        ) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static final class Occurrences {
        private final String[] words;
        private final int[][] indexes;

        private Occurrences(String[] words, int[][] indexes) {
            this.words = words;
            this.indexes = indexes;
        }
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] copiedWords = words.clone();
        String[] sortedUnique = new String[copiedWords.length];
        int count = 0;

        for (String word : copiedWords) {
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("Vocabulary words must be nonempty");
            }

            String normalized = normalizeLetters(word);
            int position = insertionPoint(sortedUnique, count, normalized);
            if (position < count && sortedUnique[position].equals(normalized)) {
                continue;
            }

            for (int i = count; i > position; i--) {
                sortedUnique[i] = sortedUnique[i - 1];
            }
            sortedUnique[position] = normalized;
            count++;
        }

        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = sortedUnique[i];
        }
        return result;
    }

    private static String[] tokenize(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

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
        int tokenIndex = 0;
        int position = 0;
        while (position < sentence.length()) {
            while (position < sentence.length()
                    && !isAsciiLetter(sentence.charAt(position))) {
                position++;
            }
            if (position == sentence.length()) {
                break;
            }

            int start = position;
            while (position < sentence.length()
                    && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }

            char[] chars = new char[position - start];
            for (int i = start; i < position; i++) {
                chars[i - start] = toLowerAscii(sentence.charAt(i));
            }
            tokens[tokenIndex++] = new String(chars);
        }

        return tokens;
    }

    private static Occurrences buildOccurrences(String[] tokens) {
        String[] words = new String[tokens.length];
        int[] counts = new int[tokens.length];
        int uniqueCount = 0;

        for (String token : tokens) {
            int position = insertionPoint(words, uniqueCount, token);
            if (position < uniqueCount && words[position].equals(token)) {
                counts[position]++;
                continue;
            }

            for (int i = uniqueCount; i > position; i--) {
                words[i] = words[i - 1];
                counts[i] = counts[i - 1];
            }
            words[position] = token;
            counts[position] = 1;
            uniqueCount++;
        }

        String[] uniqueWords = new String[uniqueCount];
        int[][] indexes = new int[uniqueCount][];
        int[] nextIndex = new int[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            uniqueWords[i] = words[i];
            indexes[i] = new int[counts[i]];
        }

        for (int tokenIndex = 0; tokenIndex < tokens.length; tokenIndex++) {
            int wordIndex = findWord(uniqueWords, tokens[tokenIndex]);
            indexes[wordIndex][nextIndex[wordIndex]++] = tokenIndex;
        }

        return new Occurrences(uniqueWords, indexes);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }
        return normalizeLetters(prefix);
    }

    private static String normalizeLetters(String value) {
        char[] chars = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Expected ASCII letters");
            }
            chars[i] = toLowerAscii(c);
        }
        return new String(chars);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static int insertionPoint(String[] sorted, int length, String value) {
        int low = 0;
        int high = length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (sorted[middle].compareTo(value) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int findWord(String[] sorted, String value) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sorted[middle].compareTo(value);
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

    private static int compareMatches(String first, String second) {
        if (first.length() != second.length()) {
            return first.length() > second.length() ? -1 : 1;
        }
        return first.compareTo(second);
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
            int[] row = source[i];
            int[] rowCopy = new int[row.length];
            for (int j = 0; j < row.length; j++) {
                rowCopy[j] = row[j];
            }
            copy[i] = rowCopy;
        }
        return copy;
    }
}
