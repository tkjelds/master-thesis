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

    public void replaceVocabulary(String[] words) {
        String[] preparedVocabulary = prepareVocabulary(words);

        synchronized (stateMonitor) {
            if (state.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            state = new State(
                    preparedVocabulary,
                    state.tokens,
                    state.occurrenceWords,
                    state.occurrenceIndexes,
                    state.vocabularyRevision + 1L,
                    state.contextGeneration);
        }
    }

    public void setContext(String sentence) {
        ContextData preparedContext = prepareContext(sentence);

        synchronized (stateMonitor) {
            if (state.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            state = new State(
                    state.vocabulary,
                    preparedContext.tokens,
                    preparedContext.occurrenceWords,
                    preparedContext.occurrenceIndexes,
                    state.vocabularyRevision,
                    state.contextGeneration + 1L);
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
            String normalizedPrefix = normalizePrefix(prefix);
            String[] matches = new String[pinnedState.vocabulary.length];
            int matchCount = 0;

            for (String word : pinnedState.vocabulary) {
                if (startsWith(word, normalizedPrefix)
                        && findString(pinnedState.occurrenceWords, word) >= 0) {
                    matches[matchCount++] = word;
                }
            }

            String[] result = new String[matchCount];
            for (int i = 0; i < matchCount; i++) {
                result[i] = matches[i];
            }
            sortByMatchOrder(result, 0, result.length - 1);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= pinnedState.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < pinnedState.tokens.length; i++) {
                if (findString(pinnedState.vocabulary, pinnedState.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int resultIndex = 0;
            for (int i = tokenIndex + 1; i < pinnedState.tokens.length; i++) {
                if (findString(pinnedState.vocabulary, pinnedState.tokens[i]) >= 0) {
                    result[resultIndex++] = i;
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
            this.vocabulary = copyStrings(state.vocabulary);
            this.tokens = copyStrings(state.tokens);
            this.occurrenceWords = copyStrings(state.occurrenceWords);
            this.occurrenceIndexes = copyIntArrays(state.occurrenceIndexes);
            this.vocabularyRevision = state.vocabularyRevision;
            this.contextGeneration = state.contextGeneration;
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
            return copyIntArrays(occurrenceIndexes);
        }

        public long vocabularyRevision() {
            return vocabularyRevision;
        }

        public long contextGeneration() {
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

    private static final class ContextData {
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;

        private ContextData(
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
        }
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] normalized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("Vocabulary entries must be nonempty words");
            }
            normalized[i] = normalizeLetters(word, false);
        }

        sortStrings(normalized, 0, normalized.length - 1);
        int uniqueCount = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (uniqueCount == 0 || !normalized[i].equals(normalized[uniqueCount - 1])) {
                normalized[uniqueCount++] = normalized[i];
            }
        }

        String[] result = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            result[i] = normalized[i];
        }
        return result;
    }

    private static ContextData prepareContext(String sentence) {
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
            boolean isLetter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (isLetter) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                tokens[tokenIndex++] = normalizeLetters(sentence.substring(runStart, i), true);
                runStart = -1;
            }
        }

        String[] sortedTokens = copyStrings(tokens);
        sortStrings(sortedTokens, 0, sortedTokens.length - 1);
        int uniqueCount = 0;
        for (int i = 0; i < sortedTokens.length; i++) {
            if (uniqueCount == 0 || !sortedTokens[i].equals(sortedTokens[uniqueCount - 1])) {
                sortedTokens[uniqueCount++] = sortedTokens[i];
            }
        }

        String[] occurrenceWords = new String[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            occurrenceWords[i] = sortedTokens[i];
        }

        int[] counts = new int[uniqueCount];
        for (String token : tokens) {
            counts[findString(occurrenceWords, token)]++;
        }

        int[][] occurrenceIndexes = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
        }

        int[] cursors = new int[uniqueCount];
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = findString(occurrenceWords, tokens[i]);
            occurrenceIndexes[wordIndex][cursors[wordIndex]++] = i;
        }

        return new ContextData(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }
        return normalizeLetters(prefix, true);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (!allowEmpty && value.length() == 0) {
            throw new IllegalArgumentException("Word must not be empty");
        }

        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Expected ASCII letters");
            }
            normalized[i] = toLowerAscii(c);
        }
        return new String(normalized);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
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

    private static int findString(String[] sortedValues, String target) {
        int low = 0;
        int high = sortedValues.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = compareStrings(sortedValues[middle], target);
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

    private static void sortStrings(String[] values, int low, int high) {
        int i = low;
        int j = high;
        if (low >= high) {
            return;
        }

        String pivot = values[low + (high - low) / 2];
        while (i <= j) {
            while (compareStrings(values[i], pivot) < 0) {
                i++;
            }
            while (compareStrings(values[j], pivot) > 0) {
                j--;
            }
            if (i <= j) {
                String temporary = values[i];
                values[i] = values[j];
                values[j] = temporary;
                i++;
                j--;
            }
        }

        if (low < j) {
            sortStrings(values, low, j);
        }
        if (i < high) {
            sortStrings(values, i, high);
        }
    }

    private static void sortByMatchOrder(String[] values, int low, int high) {
        int i = low;
        int j = high;
        if (low >= high) {
            return;
        }

        String pivot = values[low + (high - low) / 2];
        while (i <= j) {
            while (compareMatchOrder(values[i], pivot) < 0) {
                i++;
            }
            while (compareMatchOrder(values[j], pivot) > 0) {
                j--;
            }
            if (i <= j) {
                String temporary = values[i];
                values[i] = values[j];
                values[j] = temporary;
                i++;
                j--;
            }
        }

        if (low < j) {
            sortByMatchOrder(values, low, j);
        }
        if (i < high) {
            sortByMatchOrder(values, i, high);
        }
    }

    private static int compareStrings(String left, String right) {
        int commonLength = left.length() < right.length() ? left.length() : right.length();
        for (int i = 0; i < commonLength; i++) {
            char leftChar = left.charAt(i);
            char rightChar = right.charAt(i);
            if (leftChar != rightChar) {
                return leftChar < rightChar ? -1 : 1;
            }
        }
        if (left.length() == right.length()) {
            return 0;
        }
        return left.length() < right.length() ? -1 : 1;
    }

    private static int compareMatchOrder(String left, String right) {
        if (left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return compareStrings(left, right);
    }

    private static String[] copyStrings(String[] values) {
        String[] copy = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i];
        }
        return copy;
    }

    private static int[][] copyIntArrays(int[][] values) {
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
