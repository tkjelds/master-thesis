@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private Vocabulary vocabulary = new Vocabulary(new String[0]);
    private Context context =
            new Context(new String[0], new String[0], new int[0][]);

    private long vocabularyRevision;
    private long contextGeneration;

    public LexicalGraphProvider() {
    }

    public void replaceVocabulary(String[] words) {
        Vocabulary prepared = prepareVocabulary(words);
        synchronized (stateMonitor) {
            if (vocabularyRevision == 0x7fffffffffffffffL) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        Context prepared = prepareContext(sentence);
        synchronized (stateMonitor) {
            if (contextGeneration == 0x7fffffffffffffffL) {
                throw new IllegalStateException("Context generation overflow");
            }
            context = prepared;
            contextGeneration++;
        }
    }

    public Session openSession() {
        synchronized (stateMonitor) {
            return new Session(
                    vocabulary, context, vocabularyRevision, contextGeneration);
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
            String normalized = normalizeLetters(prefix, true);
            String[] words = vocabulary.words;
            String[] present = context.occurrenceWords;
            int capacity = words.length < present.length
                    ? words.length : present.length;
            String[] matches = new String[capacity];
            int count = 0;
            int i = 0;
            int j = 0;

            while (i < words.length && j < present.length) {
                int comparison = words[i].compareTo(present[j]);
                if (comparison < 0) {
                    i++;
                } else if (comparison > 0) {
                    j++;
                } else {
                    if (words[i].startsWith(normalized)) {
                        matches[count++] = words[i];
                    }
                    i++;
                    j++;
                }
            }

            String[] result = copyPrefix(matches, count);
            sort(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int[] matches = new int[context.tokens.length - tokenIndex - 1];
            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (binarySearch(vocabulary.words, context.tokens[i]) >= 0) {
                    matches[count++] = i;
                }
            }

            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = matches[i];
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary, context, vocabularyRevision, contextGeneration);
        }
    }

    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;

        // occurrenceIndexes[i] contains the positions of occurrenceWords[i].
        // Vocabulary and occurrence words are lexically sorted.
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;

        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(
                Vocabulary vocabulary,
                Context context,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = copyPrefix(
                    vocabulary.words, vocabulary.words.length);
            this.tokens = copyPrefix(context.tokens, context.tokens.length);
            this.occurrenceWords = copyPrefix(
                    context.occurrenceWords, context.occurrenceWords.length);
            this.occurrenceIndexes = new int[context.occurrenceIndexes.length][];

            for (int i = 0; i < occurrenceIndexes.length; i++) {
                int[] source = context.occurrenceIndexes[i];
                int[] copy = new int[source.length];
                for (int j = 0; j < source.length; j++) {
                    copy[j] = source[j];
                }
                occurrenceIndexes[i] = copy;
            }

            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
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

        private Context(
                String[] tokens,
                String[] occurrenceWords,
                int[][] occurrenceIndexes) {
            this.tokens = tokens;
            this.occurrenceWords = occurrenceWords;
            this.occurrenceIndexes = occurrenceIndexes;
        }
    }

    private static Vocabulary prepareVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] normalized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            normalized[i] = normalizeLetters(words[i], false);
        }
        sort(normalized, false);
        return new Vocabulary(uniqueSorted(normalized));
    }

    private static Context prepareContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        int tokenCount = 0;
        boolean insideToken = false;
        for (int i = 0; i < sentence.length(); i++) {
            if (isAsciiLetter(sentence.charAt(i))) {
                if (!insideToken) {
                    tokenCount++;
                    insideToken = true;
                }
            } else {
                insideToken = false;
            }
        }

        String[] tokens = new String[tokenCount];
        int position = 0;
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
            tokens[tokenIndex++] = lowercaseRange(sentence, start, position);
        }

        String[] sortedTokens = copyPrefix(tokens, tokens.length);
        sort(sortedTokens, false);
        String[] occurrenceWords = uniqueSorted(sortedTokens);
        int[] counts = new int[occurrenceWords.length];

        for (int i = 0; i < tokens.length; i++) {
            counts[binarySearch(occurrenceWords, tokens[i])]++;
        }

        int[][] occurrenceIndexes = new int[occurrenceWords.length][];
        for (int i = 0; i < occurrenceWords.length; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
            counts[i] = 0;
        }

        for (int i = 0; i < tokens.length; i++) {
            int wordIndex = binarySearch(occurrenceWords, tokens[i]);
            occurrenceIndexes[wordIndex][counts[wordIndex]++] = i;
        }

        return new Context(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        if (!allowEmpty && value.length() == 0) {
            throw new IllegalArgumentException("Word must not be empty");
        }

        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiLetter(value.charAt(i))) {
                throw new IllegalArgumentException(
                        "Only ASCII letters are permitted");
            }
        }
        return lowercaseRange(value, 0, value.length());
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z');
    }

    private static String lowercaseRange(String value, int start, int end) {
        char[] characters = new char[end - start];
        for (int i = start; i < end; i++) {
            char character = value.charAt(i);
            if (character >= 'A' && character <= 'Z') {
                character = (char) (character + ('a' - 'A'));
            }
            characters[i - start] = character;
        }
        return new String(characters);
    }

    private static String[] copyPrefix(String[] source, int length) {
        String[] copy = new String[length];
        for (int i = 0; i < length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static String[] uniqueSorted(String[] sorted) {
        if (sorted.length == 0) {
            return new String[0];
        }

        int count = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (!sorted[i].equals(sorted[i - 1])) {
                count++;
            }
        }

        String[] unique = new String[count];
        unique[0] = sorted[0];
        int next = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (!sorted[i].equals(sorted[i - 1])) {
                unique[next++] = sorted[i];
            }
        }
        return unique;
    }

    private static int binarySearch(String[] sorted, String word) {
        int low = 0;
        int high = sorted.length - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = sorted[middle].compareTo(word);
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

    private static void sort(String[] values, boolean lengthFirst) {
        if (values.length > 1) {
            mergeSort(
                    values, new String[values.length],
                    0, values.length, lengthFirst);
        }
    }

    private static void mergeSort(
            String[] values,
            String[] buffer,
            int start,
            int end,
            boolean lengthFirst) {
        if (end - start < 2) {
            return;
        }

        int middle = start + (end - start) / 2;
        mergeSort(values, buffer, start, middle, lengthFirst);
        mergeSort(values, buffer, middle, end, lengthFirst);

        int left = start;
        int right = middle;
        int output = start;

        while (left < middle && right < end) {
            if (compare(values[left], values[right], lengthFirst) <= 0) {
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

    private static int compare(
            String left, String right, boolean lengthFirst) {
        if (lengthFirst && left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return left.compareTo(right);
    }
}
