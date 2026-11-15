@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private String[] vocabulary = new String[0];
    private String[] tokens = new String[0];
    private String[] occurrenceWords = new String[0];
    private int[][] occurrenceIndexes = new int[0][];
    private long vocabularyRevision = 0L;
    private long contextGeneration = 0L;

    public void replaceVocabulary(String[] words) {
        String[] preparedVocabulary = prepareVocabulary(words);

        synchronized (stateMonitor) {
            if (vocabularyRevision == Long.MAX_VALUE) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = preparedVocabulary;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        String[] preparedTokens = tokenize(sentence);
        OccurrenceData occurrences = buildOccurrences(preparedTokens);

        synchronized (stateMonitor) {
            if (contextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("Context generation overflow");
            }
            tokens = preparedTokens;
            occurrenceWords = occurrences.words;
            occurrenceIndexes = occurrences.indexes;
            contextGeneration++;
        }
    }

    public Session openSession() {
        String[] capturedVocabulary;
        String[] capturedTokens;
        String[] capturedOccurrenceWords;
        int[][] capturedOccurrenceIndexes;
        long capturedVocabularyRevision;
        long capturedContextGeneration;

        synchronized (stateMonitor) {
            capturedVocabulary = vocabulary;
            capturedTokens = tokens;
            capturedOccurrenceWords = occurrenceWords;
            capturedOccurrenceIndexes = occurrenceIndexes;
            capturedVocabularyRevision = vocabularyRevision;
            capturedContextGeneration = contextGeneration;
        }

        return new Session(
                capturedVocabulary,
                capturedTokens,
                capturedOccurrenceWords,
                capturedOccurrenceIndexes,
                capturedVocabularyRevision,
                capturedContextGeneration);
    }

    public Snapshot snapshot() {
        String[] capturedVocabulary;
        String[] capturedTokens;
        String[] capturedOccurrenceWords;
        int[][] capturedOccurrenceIndexes;
        long capturedVocabularyRevision;
        long capturedContextGeneration;

        synchronized (stateMonitor) {
            capturedVocabulary = vocabulary;
            capturedTokens = tokens;
            capturedOccurrenceWords = occurrenceWords;
            capturedOccurrenceIndexes = occurrenceIndexes;
            capturedVocabularyRevision = vocabularyRevision;
            capturedContextGeneration = contextGeneration;
        }

        return new Snapshot(
                capturedVocabulary,
                capturedTokens,
                capturedOccurrenceWords,
                capturedOccurrenceIndexes,
                capturedVocabularyRevision,
                capturedContextGeneration);
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
                long contextGeneration) {
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
            int count = 0;

            for (int i = 0; i < vocabulary.length; i++) {
                String word = vocabulary[i];
                if (word.startsWith(normalizedPrefix)
                        && findOccurrenceWord(word) >= 0) {
                    matches[count++] = word;
                }
            }

            String[] result = new String[count];
            System.arraycopy(matches, 0, result, 0, count);
            sortMatches(result);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (containsVocabularyWord(tokens[i])) {
                    count++;
                }
            }

            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < tokens.length; i++) {
                if (containsVocabularyWord(tokens[i])) {
                    result[next++] = i;
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
                    contextGeneration);
        }

        private int findOccurrenceWord(String word) {
            int low = 0;
            int high = occurrenceWords.length - 1;
            while (low <= high) {
                int middle = low + (high - low) / 2;
                int comparison = occurrenceWords[middle].compareTo(word);
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

        private boolean containsVocabularyWord(String word) {
            int low = 0;
            int high = vocabulary.length - 1;
            while (low <= high) {
                int middle = low + (high - low) / 2;
                int comparison = vocabulary[middle].compareTo(word);
                if (comparison == 0) {
                    return true;
                }
                if (comparison < 0) {
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return false;
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

        public int[][] occurrenceIndexes() {
            return copyIndexes(occurrenceIndexes);
        }
    }

    private static final class OccurrenceData {
        final String[] words;
        final int[][] indexes;

        OccurrenceData(String[] words, int[][] indexes) {
            this.words = words;
            this.indexes = indexes;
        }
    }

    private static String[] prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] unique = new String[words.length];
        int uniqueCount = 0;

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word == null || word.length() == 0) {
                throw new IllegalArgumentException("Vocabulary words must be nonempty");
            }

            char[] normalized = new char[word.length()];
            for (int j = 0; j < word.length(); j++) {
                char c = word.charAt(j);
                if (!isAsciiLetter(c)) {
                    throw new IllegalArgumentException("Vocabulary words must contain only ASCII letters");
                }
                normalized[j] = toLowerAscii(c);
            }

            String normalizedWord = new String(normalized);
            boolean alreadyPresent = false;
            for (int j = 0; j < uniqueCount; j++) {
                if (unique[j].equals(normalizedWord)) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) {
                unique[uniqueCount++] = normalizedWord;
            }
        }

        String[] result = new String[uniqueCount];
        System.arraycopy(unique, 0, result, 0, uniqueCount);
        sortStrings(result);
        return result;
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

        String[] result = new String[tokenCount];
        int tokenIndex = 0;
        int position = 0;
        while (position < sentence.length()) {
            while (position < sentence.length()
                    && !isAsciiLetter(sentence.charAt(position))) {
                position++;
            }
            if (position >= sentence.length()) {
                break;
            }

            int start = position;
            while (position < sentence.length()
                    && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }

            char[] normalized = new char[position - start];
            for (int i = start; i < position; i++) {
                normalized[i - start] = toLowerAscii(sentence.charAt(i));
            }
            result[tokenIndex++] = new String(normalized);
        }
        return result;
    }

    private static OccurrenceData buildOccurrences(String[] tokens) {
        String[] sortedTokens = copyStrings(tokens);
        sortStrings(sortedTokens);

        int distinctCount = 0;
        for (int i = 0; i < sortedTokens.length; i++) {
            if (i == 0 || !sortedTokens[i].equals(sortedTokens[i - 1])) {
                distinctCount++;
            }
        }

        String[] words = new String[distinctCount];
        int wordPosition = 0;
        for (int i = 0; i < sortedTokens.length; i++) {
            if (i == 0 || !sortedTokens[i].equals(sortedTokens[i - 1])) {
                words[wordPosition++] = sortedTokens[i];
            }
        }

        int[] counts = new int[distinctCount];
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(words, tokens[i]);
            counts[wordIndex]++;
        }

        int[][] indexes = new int[distinctCount][];
        for (int i = 0; i < distinctCount; i++) {
            indexes[i] = new int[counts[i]];
        }

        int[] nextPositions = new int[distinctCount];
        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(words, tokens[i]);
            indexes[wordIndex][nextPositions[wordIndex]++] = i;
        }

        return new OccurrenceData(words, indexes);
    }

    private static int binarySearch(String[] sorted, String value) {
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

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Prefix must not be null");
        }

        char[] normalized = new char[prefix.length()];
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Prefix must contain only ASCII letters");
            }
            normalized[i] = toLowerAscii(c);
        }
        return new String(normalized);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toLowerAscii(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char) (c + ('a' - 'A'));
        }
        return c;
    }

    private static String[] copyStrings(String[] source) {
        String[] result = new String[source.length];
        System.arraycopy(source, 0, result, 0, source.length);
        return result;
    }

    private static int[][] copyIndexes(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            result[i] = new int[source[i].length];
            System.arraycopy(source[i], 0, result[i], 0, source[i].length);
        }
        return result;
    }

    private static void sortStrings(String[] values) {
        mergeSort(values, false);
    }

    private static void sortMatches(String[] values) {
        mergeSort(values, true);
    }

    private static void mergeSort(String[] values, boolean matchOrder) {
        int length = values.length;
        if (length < 2) {
            return;
        }

        String[] temporary = new String[length];
        int width = 1;
        while (width < length) {
            int left = 0;
            while (left < length) {
                int middle = left + Math.min(width, length - left);
                int right = middle + Math.min(width, length - middle);
                int first = left;
                int second = middle;
                int output = left;

                while (first < middle && second < right) {
                    if (compare(values[first], values[second], matchOrder) <= 0) {
                        temporary[output++] = values[first++];
                    } else {
                        temporary[output++] = values[second++];
                    }
                }
                while (first < middle) {
                    temporary[output++] = values[first++];
                }
                while (second < right) {
                    temporary[output++] = values[second++];
                }
                left = right;
            }

            System.arraycopy(temporary, 0, values, 0, length);
            if (width > length / 2) {
                break;
            }
            width *= 2;
        }
    }

    private static int compare(String first, String second, boolean matchOrder) {
        if (matchOrder) {
            int lengthComparison = second.length() - first.length();
            if (lengthComparison != 0) {
                return lengthComparison;
            }
        }
        return first.compareTo(second);
    }
}
