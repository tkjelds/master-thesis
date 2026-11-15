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
            0L);

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("words must not be null");
        }

        String[] preparedVocabulary = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("vocabulary words must be nonempty");
            }
            for (int j = 0; j < word.length(); j++) {
                if (!isAsciiLetter(word.charAt(j))) {
                    throw new IllegalArgumentException("vocabulary words must contain only ASCII letters");
                }
            }
            preparedVocabulary[i] = lowercaseAscii(word);
        }

        sortStrings(preparedVocabulary);
        int uniqueCount = 0;
        for (int i = 0; i < preparedVocabulary.length; i++) {
            if (i == 0 || !preparedVocabulary[i].equals(preparedVocabulary[i - 1])) {
                preparedVocabulary[uniqueCount++] = preparedVocabulary[i];
            }
        }
        String[] uniqueVocabulary = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            uniqueVocabulary[i] = preparedVocabulary[i];
        }

        synchronized (stateMonitor) {
            State current = state;
            if (current.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("vocabulary revision overflow");
            }
            state = new State(
                    uniqueVocabulary,
                    current.tokens,
                    current.occurrenceWords,
                    current.occurrenceIndexes,
                    current.vocabularyRevision + 1L,
                    current.contextGeneration);
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("sentence must not be null");
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

        String[] preparedTokens = new String[tokenCount];
        int tokenIndex = 0;
        int tokenStart = -1;
        for (int i = 0; i <= sentence.length(); i++) {
            boolean letter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (letter) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
            } else if (tokenStart >= 0) {
                preparedTokens[tokenIndex++] = lowercaseAscii(sentence.substring(tokenStart, i));
                tokenStart = -1;
            }
        }

        String[] sortedWords = new String[preparedTokens.length];
        for (int i = 0; i < preparedTokens.length; i++) {
            sortedWords[i] = preparedTokens[i];
        }
        sortStrings(sortedWords);

        int distinctCount = 0;
        for (int i = 0; i < sortedWords.length; i++) {
            if (i == 0 || !sortedWords[i].equals(sortedWords[i - 1])) {
                sortedWords[distinctCount++] = sortedWords[i];
            }
        }

        String[] occurrenceWords = new String[distinctCount];
        for (int i = 0; i < distinctCount; i++) {
            occurrenceWords[i] = sortedWords[i];
        }

        int[] counts = new int[distinctCount];
        for (int i = 0; i < preparedTokens.length; i++) {
            int wordIndex = binarySearch(occurrenceWords, preparedTokens[i]);
            counts[wordIndex]++;
        }

        int[][] occurrenceIndexes = new int[distinctCount][];
        for (int i = 0; i < distinctCount; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
        }

        int[] nextPositions = new int[distinctCount];
        for (int i = 0; i < preparedTokens.length; i++) {
            int wordIndex = binarySearch(occurrenceWords, preparedTokens[i]);
            occurrenceIndexes[wordIndex][nextPositions[wordIndex]++] = i;
        }

        synchronized (stateMonitor) {
            State current = state;
            if (current.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("context generation overflow");
            }
            state = new State(
                    current.vocabulary,
                    preparedTokens,
                    occurrenceWords,
                    occurrenceIndexes,
                    current.vocabularyRevision,
                    current.contextGeneration + 1L);
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

    public static final class Session {
        private final State pinnedState;

        private Session(State pinnedState) {
            this.pinnedState = pinnedState;
        }

        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix must not be null");
            }
            for (int i = 0; i < prefix.length(); i++) {
                if (!isAsciiLetter(prefix.charAt(i))) {
                    throw new IllegalArgumentException("prefix must contain only ASCII letters");
                }
            }
            String normalizedPrefix = lowercaseAscii(prefix);

            String[] matches = new String[pinnedState.vocabulary.length];
            int matchCount = 0;
            for (int i = 0; i < pinnedState.vocabulary.length; i++) {
                String word = pinnedState.vocabulary[i];
                if (startsWith(word, normalizedPrefix)
                        && binarySearch(pinnedState.occurrenceWords, word) >= 0) {
                    matches[matchCount++] = word;
                }
            }

            for (int i = 1; i < matchCount; i++) {
                String word = matches[i];
                int j = i - 1;
                while (j >= 0 && compareMatchOrder(word, matches[j]) < 0) {
                    matches[j + 1] = matches[j];
                    j--;
                }
                matches[j + 1] = word;
            }

            String[] result = new String[matchCount];
            for (int i = 0; i < matchCount; i++) {
                result[i] = matches[i];
            }
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= pinnedState.tokens.length) {
                throw new IllegalArgumentException("invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < pinnedState.tokens.length; i++) {
                if (binarySearch(pinnedState.vocabulary, pinnedState.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int position = 0;
            for (int i = tokenIndex + 1; i < pinnedState.tokens.length; i++) {
                if (binarySearch(pinnedState.vocabulary, pinnedState.tokens[i]) >= 0) {
                    result[position++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(pinnedState);
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
            vocabulary = copyStrings(state.vocabulary);
            tokens = copyStrings(state.tokens);
            occurrenceWords = copyStrings(state.occurrenceWords);
            occurrenceIndexes = copyIndexes(state.occurrenceIndexes);
            vocabularyRevision = state.vocabularyRevision;
            contextGeneration = state.contextGeneration;
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
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static String lowercaseAscii(String value) {
        char[] result = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            result[i] = current >= 'A' && current <= 'Z'
                    ? (char) (current + ('a' - 'A'))
                    : current;
        }
        return new String(result);
    }

    private static boolean startsWith(String value, String prefix) {
        if (prefix.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (value.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int compareMatchOrder(String first, String second) {
        if (first.length() != second.length()) {
            return first.length() > second.length() ? -1 : 1;
        }
        return first.compareTo(second);
    }

    private static int binarySearch(String[] sortedValues, String value) {
        int low = 0;
        int high = sortedValues.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sortedValues[middle].compareTo(value);
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

    private static void sortStrings(String[] values) {
        for (int start = values.length / 2 - 1; start >= 0; start--) {
            siftDown(values, start, values.length);
        }
        for (int end = values.length - 1; end > 0; end--) {
            String temporary = values[0];
            values[0] = values[end];
            values[end] = temporary;
            siftDown(values, 0, end);
        }
    }

    private static void siftDown(String[] values, int root, int size) {
        while (root * 2 + 1 < size) {
            int child = root * 2 + 1;
            if (child + 1 < size && values[child].compareTo(values[child + 1]) < 0) {
                child++;
            }
            if (values[root].compareTo(values[child]) >= 0) {
                return;
            }
            String temporary = values[root];
            values[root] = values[child];
            values[child] = temporary;
            root = child;
        }
    }

    private static String[] copyStrings(String[] values) {
        String[] copy = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i];
        }
        return copy;
    }

    private static int[][] copyIndexes(int[][] values) {
        int[][] copy = new int[values.length][];
        for (int i = 0; i < values.length; i++) {
            int[] row = new int[values[i].length];
            for (int j = 0; j < values[i].length; j++) {
                row[j] = values[i][j];
            }
            copy[i] = row;
        }
        return copy;
    }
}
