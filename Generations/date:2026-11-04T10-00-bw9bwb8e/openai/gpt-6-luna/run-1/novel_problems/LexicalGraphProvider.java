@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();
    private State state;

    public LexicalGraphProvider() {
        state = new State(
                new String[0],
                new String[0],
                new String[0],
                new int[0][],
                0L,
                0L
        );
    }

    public void replaceVocabulary(String[] words) {
        String[] preparedVocabulary = prepareVocabulary(words);

        synchronized (stateMonitor) {
            if (state.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }

            State current = state;
            state = new State(
                    preparedVocabulary,
                    current.tokens,
                    current.occurrenceWords,
                    current.occurrenceIndexes,
                    current.vocabularyRevision + 1L,
                    current.contextGeneration
            );
        }
    }

    public void setContext(String sentence) {
        String[] preparedTokens = tokenize(sentence);
        OccurrenceIndex index = buildOccurrenceIndex(preparedTokens);

        synchronized (stateMonitor) {
            if (state.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }

            State current = state;
            state = new State(
                    current.vocabulary,
                    preparedTokens,
                    index.words,
                    index.positions,
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
        return new Session(captured);
    }

    public Snapshot snapshot() {
        State captured;
        synchronized (stateMonitor) {
            captured = state;
        }
        return new Snapshot(captured);
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] copied = words.clone();
        String[] normalized = new String[copied.length];

        for (int i = 0; i < copied.length; i++) {
            String word = copied[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("Vocabulary entries must be nonempty");
            }

            char[] chars = new char[word.length()];
            for (int j = 0; j < word.length(); j++) {
                char c = word.charAt(j);
                if (!isAsciiLetter(c)) {
                    throw new IllegalArgumentException("Vocabulary entries must contain only ASCII letters");
                }
                chars[j] = toLowerAscii(c);
            }
            normalized[i] = new String(chars);
        }

        sortLexically(normalized);
        if (normalized.length == 0) {
            return normalized;
        }

        int uniqueCount = 1;
        for (int i = 1; i < normalized.length; i++) {
            if (!normalized[i].equals(normalized[uniqueCount - 1])) {
                normalized[uniqueCount++] = normalized[i];
            }
        }

        String[] unique = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            unique[i] = normalized[i];
        }
        return unique;
    }

    private static String[] tokenize(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        int tokenCount = 0;
        boolean inRun = false;
        for (int i = 0; i < sentence.length(); i++) {
            if (isAsciiLetter(sentence.charAt(i))) {
                if (!inRun) {
                    tokenCount++;
                    inRun = true;
                }
            } else {
                inRun = false;
            }
        }

        String[] tokens = new String[tokenCount];
        int tokenIndex = 0;
        int runStart = -1;

        for (int i = 0; i <= sentence.length(); i++) {
            boolean letter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (letter) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                char[] chars = new char[i - runStart];
                for (int j = runStart; j < i; j++) {
                    chars[j - runStart] = toLowerAscii(sentence.charAt(j));
                }
                tokens[tokenIndex++] = new String(chars);
                runStart = -1;
            }
        }

        return tokens;
    }

    private static OccurrenceIndex buildOccurrenceIndex(String[] tokens) {
        if (tokens.length == 0) {
            return new OccurrenceIndex(new String[0], new int[0][]);
        }

        String[] sortedTokens = new String[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            sortedTokens[i] = tokens[i];
        }
        sortLexically(sortedTokens);

        int uniqueCount = 1;
        for (int i = 1; i < sortedTokens.length; i++) {
            if (!sortedTokens[i].equals(sortedTokens[uniqueCount - 1])) {
                sortedTokens[uniqueCount++] = sortedTokens[i];
            }
        }

        String[] words = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            words[i] = sortedTokens[i];
        }

        int[] counts = new int[uniqueCount];
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(words, tokens[i]);
            counts[wordIndex]++;
        }

        int[][] positions = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            positions[i] = new int[counts[i]];
        }

        int[] nextPosition = new int[uniqueCount];
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(words, tokens[i]);
            positions[wordIndex][nextPosition[wordIndex]++] = i;
        }

        return new OccurrenceIndex(words, positions);
    }

    private static int binarySearch(String[] sorted, String value) {
        int low = 0;
        int high = sorted.length - 1;

        while (low <= high) {
            int middle = low + ((high - low) >>> 1);
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

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static void sortLexically(String[] values) {
        if (values.length < 2) {
            return;
        }
        String[] scratch = new String[values.length];
        mergeSortLexically(values, scratch, 0, values.length);
    }

    private static void mergeSortLexically(String[] values, String[] scratch, int low, int high) {
        if (high - low < 2) {
            return;
        }

        int middle = low + ((high - low) >>> 1);
        mergeSortLexically(values, scratch, low, middle);
        mergeSortLexically(values, scratch, middle, high);

        int left = low;
        int right = middle;
        int out = low;

        while (left < middle && right < high) {
            if (values[left].compareTo(values[right]) <= 0) {
                scratch[out++] = values[left++];
            } else {
                scratch[out++] = values[right++];
            }
        }
        while (left < middle) {
            scratch[out++] = values[left++];
        }
        while (right < high) {
            scratch[out++] = values[right++];
        }
        for (int i = low; i < high; i++) {
            values[i] = scratch[i];
        }
    }

    private static void sortMatches(String[] values) {
        if (values.length < 2) {
            return;
        }
        String[] scratch = new String[values.length];
        mergeSortMatches(values, scratch, 0, values.length);
    }

    private static void mergeSortMatches(String[] values, String[] scratch, int low, int high) {
        if (high - low < 2) {
            return;
        }

        int middle = low + ((high - low) >>> 1);
        mergeSortMatches(values, scratch, low, middle);
        mergeSortMatches(values, scratch, middle, high);

        int left = low;
        int right = middle;
        int out = low;

        while (left < middle && right < high) {
            if (compareMatches(values[left], values[right]) <= 0) {
                scratch[out++] = values[left++];
            } else {
                scratch[out++] = values[right++];
            }
        }
        while (left < middle) {
            scratch[out++] = values[left++];
        }
        while (right < high) {
            scratch[out++] = values[right++];
        }
        for (int i = low; i < high; i++) {
            values[i] = scratch[i];
        }
    }

    private static int compareMatches(String left, String right) {
        if (left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return left.compareTo(right);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }

        char[] chars = new char[prefix.length()];
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Prefix must contain only ASCII letters");
            }
            chars[i] = toLowerAscii(c);
        }
        return new String(chars);
    }

    private static final class State {
        final String[] vocabulary;
        final String[] tokens;
        final String[] occurrenceWords;
        final int[][] occurrenceIndexes;
        final long vocabularyRevision;
        final long contextGeneration;

        State(
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

    private static final class OccurrenceIndex {
        final String[] words;
        final int[][] positions;

        OccurrenceIndex(String[] words, int[][] positions) {
            this.words = words;
            this.positions = positions;
        }
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(State state) {
            this.vocabulary = state.vocabulary;
            this.tokens = state.tokens;
            this.occurrenceWords = state.occurrenceWords;
            this.occurrenceIndexes = state.occurrenceIndexes;
            this.vocabularyRevision = state.vocabularyRevision;
            this.contextGeneration = state.contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizePrefix(prefix);
            String[] matches = new String[vocabulary.length];
            int count = 0;

            for (int i = 0; i < vocabulary.length; i++) {
                String word = vocabulary[i];
                if (word.startsWith(normalizedPrefix)
                        && binarySearch(occurrenceWords, word) >= 0) {
                    matches[count++] = word;
                }
            }

            String[] result = new String[count];
            for (int i = 0; i < count; i++) {
                result[i] = matches[i];
            }
            sortMatches(result);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary, tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int out = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (binarySearch(vocabulary, tokens[i]) >= 0) {
                    result[out++] = i;
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
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(State state) {
            this(
                    state.vocabulary,
                    state.tokens,
                    state.occurrenceWords,
                    state.occurrenceIndexes,
                    state.vocabularyRevision,
                    state.contextGeneration
            );
        }

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

        public String[] vocabulary() {
            return copyStrings(vocabulary);
        }

        public String[] tokens() {
            return copyStrings(tokens);
        }

        public String[] occurrenceWords() {
            return copyStrings(occurrenceWords);
        }

        public int[][] occurrenceIndexes() {
            return copyIndexes(occurrenceIndexes);
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

        public String[] getOccurrenceWords() {
            return occurrenceWords();
        }

        public int[][] getOccurrenceIndexes() {
            return occurrenceIndexes();
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
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
}
