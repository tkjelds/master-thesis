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
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] prepared = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            prepared[i] = normalizeLetters(words[i], false);
        }
        sortStrings(prepared, false);

        int distinctCount = 0;
        for (int i = 0; i < prepared.length; i++) {
            if (i == 0 || !prepared[i].equals(prepared[i - 1])) {
                distinctCount++;
            }
        }

        String[] distinct = new String[distinctCount];
        int next = 0;
        for (int i = 0; i < prepared.length; i++) {
            if (i == 0 || !prepared[i].equals(prepared[i - 1])) {
                distinct[next++] = prepared[i];
            }
        }
        Vocabulary replacement = new Vocabulary(distinct);

        synchronized (stateMonitor) {
            if (vocabularyRevision == 9223372036854775807L) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = replacement;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        Context replacement = prepareContext(sentence);
        synchronized (stateMonitor) {
            if (contextGeneration == 9223372036854775807L) {
                throw new IllegalStateException("Context generation overflow");
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

        private Session(
                Vocabulary vocabulary,
                Context context,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            String normalizedPrefix = normalizeLetters(prefix, true);
            int count = 0;

            for (int i = 0; i < vocabulary.words.length; i++) {
                String word = vocabulary.words[i];
                if (word.startsWith(normalizedPrefix)
                        && findWord(context.occurrenceWords, word) >= 0) {
                    count++;
                }
            }

            String[] matches = new String[count];
            int next = 0;
            for (int i = 0; i < vocabulary.words.length; i++) {
                String word = vocabulary.words[i];
                if (word.startsWith(normalizedPrefix)
                        && findWord(context.occurrenceWords, word) >= 0) {
                    matches[next++] = word;
                }
            }
            sortStrings(matches, true);
            return matches;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findWord(vocabulary.words, context.tokens[i]) >= 0) {
                    count++;
                }
            }

            int[] result = new int[count];
            int next = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (findWord(vocabulary.words, context.tokens[i]) >= 0) {
                    result[next++] = i;
                }
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary.words,
                    context.tokens,
                    context.occurrenceWords,
                    context.occurrenceIndexes,
                    vocabularyRevision,
                    contextGeneration);
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

        // Each word corresponds to the index array at the same position.
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

        private Context(String[] tokens, String[] occurrenceWords, int[][] occurrenceIndexes) {
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
                continue;
            }
            tokenCount++;
            while (position < sentence.length() && isAsciiLetter(sentence.charAt(position))) {
                position++;
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
            while (position < sentence.length() && isAsciiLetter(sentence.charAt(position))) {
                position++;
            }
            char[] letters = new char[position - start];
            for (int i = 0; i < letters.length; i++) {
                letters[i] = lowercaseAscii(sentence.charAt(start + i));
            }
            tokens[tokenIndex++] = new String(letters);
        }

        String[] sorted = copyStrings(tokens);
        sortStrings(sorted, false);
        int distinctCount = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || !sorted[i].equals(sorted[i - 1])) {
                distinctCount++;
            }
        }

        String[] occurrenceWords = new String[distinctCount];
        int wordIndex = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || !sorted[i].equals(sorted[i - 1])) {
                occurrenceWords[wordIndex++] = sorted[i];
            }
        }

        int[] counts = new int[distinctCount];
        for (int i = 0; i < tokens.length; i++) {
            counts[findWord(occurrenceWords, tokens[i])]++;
        }
        int[][] occurrenceIndexes = new int[distinctCount][];
        for (int i = 0; i < distinctCount; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < tokens.length; i++) {
            int index = findWord(occurrenceWords, tokens[i]);
            occurrenceIndexes[index][counts[index]++] = i;
        }
        return new Context(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("Expected an ASCII-letter string");
        }
        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Expected an ASCII-letter string");
            }
            normalized[i] = lowercaseAscii(c);
        }
        return new String(normalized);
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

    private static int findWord(String[] words, String target) {
        int low = 0;
        int high = words.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = words[middle].compareTo(target);
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
            mergeSort(values, new String[values.length], 0, values.length, matchOrder);
        }
    }

    private static void mergeSort(
            String[] values, String[] buffer, int start, int end, boolean matchOrder) {
        if (end - start <= 1) {
            return;
        }
        int middle = start + (end - start) / 2;
        mergeSort(values, buffer, start, middle, matchOrder);
        mergeSort(values, buffer, middle, end, matchOrder);

        int left = start;
        int right = middle;
        int output = start;
        while (left < middle && right < end) {
            if (compareStrings(values[left], values[right], matchOrder) <= 0) {
                buffer[output++] = values[left++];
            } else {
                buffer[output++] = values[right++];
            }
        }
        while (left < middle) {
            buffer[output++] = values[left++];
        }
        while (right < end) {
            buffer[output++] = values[right++];
        }
        for (int i = start; i < end; i++) {
            values[i] = buffer[i];
        }
    }

    private static int compareStrings(String left, String right, boolean matchOrder) {
        if (matchOrder) {
            if (left.length() > right.length()) {
                return -1;
            }
            if (left.length() < right.length()) {
                return 1;
            }
        }
        return left.compareTo(right);
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
