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
        String[] preparedVocabulary = prepareVocabulary(words);
        synchronized (stateMonitor) {
            State current = state;
            if (current.vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            state = new State(
                    preparedVocabulary,
                    current.tokens,
                    current.occurrenceWords,
                    current.occurrenceIndexes,
                    current.vocabularyRevision + 1L,
                    current.contextGeneration);
        }
    }

    public void setContext(String sentence) {
        String[] preparedTokens = tokenize(sentence);
        Occurrences occurrences = buildOccurrences(preparedTokens);
        synchronized (stateMonitor) {
            State current = state;
            if (current.contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            state = new State(
                    current.vocabulary,
                    preparedTokens,
                    occurrences.words,
                    occurrences.indexes,
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
        return new Snapshot(
                captured.vocabulary,
                captured.tokens,
                captured.occurrenceWords,
                captured.occurrenceIndexes,
                captured.vocabularyRevision,
                captured.contextGeneration);
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }
        String[] normalized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("Vocabulary words must be nonempty");
            }
            char[] characters = new char[word.length()];
            for (int j = 0; j < word.length(); j++) {
                char character = word.charAt(j);
                if (!isAsciiLetter(character)) {
                    throw new IllegalArgumentException("Vocabulary words must use ASCII letters");
                }
                characters[j] = toLowerAscii(character);
            }
            normalized[i] = new String(characters);
        }

        sortLexically(normalized);
        int distinctCount = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                normalized[distinctCount++] = normalized[i];
            }
        }
        String[] vocabulary = new String[distinctCount];
        for (int i = 0; i < distinctCount; i++) {
            vocabulary[i] = normalized[i];
        }
        return vocabulary;
    }

    private static void sortLexically(String[] values) {
        for (int i = 1; i < values.length; i++) {
            String value = values[i];
            int position = i;
            while (position > 0 && value.compareTo(values[position - 1]) < 0) {
                values[position] = values[position - 1];
                position--;
            }
            values[position] = value;
        }
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
        int tokenStart = -1;
        for (int i = 0; i <= sentence.length(); i++) {
            boolean letter = i < sentence.length() && isAsciiLetter(sentence.charAt(i));
            if (letter) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
            } else if (tokenStart >= 0) {
                int length = i - tokenStart;
                char[] characters = new char[length];
                for (int j = 0; j < length; j++) {
                    characters[j] = toLowerAscii(sentence.charAt(tokenStart + j));
                }
                tokens[tokenIndex++] = new String(characters);
                tokenStart = -1;
            }
        }
        return tokens;
    }

    private static Occurrences buildOccurrences(String[] tokens) {
        String[] words = new String[tokens.length];
        int[] counts = new int[tokens.length];
        int uniqueCount = 0;
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = findWord(words, uniqueCount, tokens[i]);
            if (wordIndex < 0) {
                wordIndex = uniqueCount++;
                words[wordIndex] = tokens[i];
            }
            counts[wordIndex]++;
        }

        String[] uniqueWords = new String[uniqueCount];
        int[][] indexes = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            uniqueWords[i] = words[i];
            indexes[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = findWord(uniqueWords, uniqueCount, tokens[i]);
            indexes[wordIndex][counts[wordIndex]++] = i;
        }
        return new Occurrences(uniqueWords, indexes);
    }

    private static int findWord(String[] words, int length, String target) {
        for (int i = 0; i < length; i++) {
            if (words[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z');
    }

    private static char toLowerAscii(char character) {
        if (character >= 'A' && character <= 'Z') {
            return (char) (character + ('a' - 'A'));
        }
        return character;
    }

    private static int findSortedWord(String[] words, String target) {
        int low = 0;
        int high = words.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = words[middle].compareTo(target);
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

    private static boolean occursIn(String[] words, String target) {
        return findWord(words, words.length, target) >= 0;
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

    private static final class Occurrences {
        private final String[] words;
        private final int[][] indexes;

        private Occurrences(String[] words, int[][] indexes) {
            this.words = words;
            this.indexes = indexes;
        }
    }

    public static final class Session {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Session(State captured) {
            this.vocabulary = captured.vocabulary;
            this.tokens = captured.tokens;
            this.occurrenceWords = captured.occurrenceWords;
            this.occurrenceIndexes = captured.occurrenceIndexes;
            this.vocabularyRevision = captured.vocabularyRevision;
            this.contextGeneration = captured.contextGeneration;
        }

        public String[] findMatches(String prefix) {
            if (prefix == null) {
                throw new IllegalArgumentException("Prefix must not be null");
            }
            char[] characters = new char[prefix.length()];
            for (int i = 0; i < prefix.length(); i++) {
                char character = prefix.charAt(i);
                if (!isAsciiLetter(character)) {
                    throw new IllegalArgumentException("Prefix must use ASCII letters");
                }
                characters[i] = toLowerAscii(character);
            }
            String normalizedPrefix = new String(characters);

            String[] matches = new String[vocabulary.length];
            int matchCount = 0;
            for (int i = 0; i < vocabulary.length; i++) {
                String word = vocabulary[i];
                if (word.startsWith(normalizedPrefix) && occursIn(occurrenceWords, word)) {
                    matches[matchCount++] = word;
                }
            }
            for (int i = 1; i < matchCount; i++) {
                String word = matches[i];
                int position = i;
                while (position > 0 && comesBefore(word, matches[position - 1])) {
                    matches[position] = matches[position - 1];
                    position--;
                }
                matches[position] = word;
            }
            String[] result = new String[matchCount];
            for (int i = 0; i < matchCount; i++) {
                result[i] = matches[i];
            }
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Token index is out of range");
            }
            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (findSortedWord(vocabulary, tokens[i]) >= 0) {
                    count++;
                }
            }
            int[] dependencies = new int[count];
            int position = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (findSortedWord(vocabulary, tokens[i]) >= 0) {
                    dependencies[position++] = i;
                }
            }
            return dependencies;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary,
                    tokens,
                    occurrenceWords,
                    occurrenceIndexes,
                    vocabularyRevision,
                    contextGeneration);
        }

        private static boolean comesBefore(String left, String right) {
            if (left.length() != right.length()) {
                return left.length() > right.length();
            }
            return left.compareTo(right) < 0;
        }
    }

    public static final class Snapshot {
        private final String[] vocabulary;
        private final String[] tokens;
        private final String[] occurrenceWords;
        private final int[][] occurrenceIndexes;
        private final long vocabularyRevision;
        private final long contextGeneration;

        private Snapshot(
                String[] vocabulary,
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = copy(vocabulary);
            this.tokens = copy(tokens);
            this.occurrenceWords = copy(occurrenceWords);
            this.occurrenceIndexes = copy(occurrenceIndexes);
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] getVocabulary() {
            return copy(vocabulary);
        }

        public String[] getTokens() {
            return copy(tokens);
        }

        public String[] getOccurrenceWords() {
            return copy(occurrenceWords);
        }

        public int[][] getOccurrenceIndexes() {
            return copy(occurrenceIndexes);
        }

        public long getVocabularyRevision() {
            return vocabularyRevision;
        }

        public long getContextGeneration() {
            return contextGeneration;
        }

        private static String[] copy(String[] source) {
            String[] result = new String[source.length];
            for (int i = 0; i < source.length; i++) {
                result[i] = source[i];
            }
            return result;
        }

        private static int[][] copy(int[][] source) {
            int[][] result = new int[source.length][];
            for (int i = 0; i < source.length; i++) {
                int[] row = new int[source[i].length];
                for (int j = 0; j < source[i].length; j++) {
                    row[j] = source[i][j];
                }
                result[i] = row;
            }
            return result;
        }
    }
}
