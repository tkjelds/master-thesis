@LexicalGraphProvider.ThreadSafe
public final class LexicalGraphProvider {
    @interface ThreadSafe {}

    private final Object stateMonitor = new Object();

    private Vocabulary vocabulary = new Vocabulary(new String[0]);
    private Context context = new Context(
            new String[0], new String[0], new int[0][]);
    private long vocabularyRevision = 0;
    private long contextGeneration = 0;

    public void replaceVocabulary(String[] words) {
        if (words == null) {
            throw new IllegalArgumentException("Vocabulary must not be null");
        }

        String[] normalized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            normalized[i] = normalizeLetters(words[i], false);
        }
        sort(normalized, false);

        int uniqueCount = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                uniqueCount++;
            }
        }

        String[] unique = new String[uniqueCount];
        int next = 0;
        for (int i = 0; i < normalized.length; i++) {
            if (i == 0 || !normalized[i].equals(normalized[i - 1])) {
                unique[next++] = normalized[i];
            }
        }
        Vocabulary prepared = new Vocabulary(unique);

        synchronized (stateMonitor) {
            if (vocabularyRevision == 9223372036854775807L) {
                throw new IllegalStateException("Vocabulary revision overflow");
            }
            vocabulary = prepared;
            vocabularyRevision++;
        }
    }

    public void setContext(String sentence) {
        if (sentence == null) {
            throw new IllegalArgumentException("Sentence must not be null");
        }

        Context prepared = prepareContext(sentence);
        synchronized (stateMonitor) {
            if (contextGeneration == 9223372036854775807L) {
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
            sort(result, true);
            return result;
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
                throw new IllegalArgumentException("Invalid token index");
            }

            int[] candidates = new int[context.tokens.length - tokenIndex - 1];
            int count = 0;
            for (int i = tokenIndex + 1; i < context.tokens.length; i++) {
                if (binarySearch(vocabulary.words, context.tokens[i]) >= 0) {
                    candidates[count++] = i;
                }
            }

            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = candidates[i];
            }
            return result;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                    vocabulary, context, vocabularyRevision, contextGeneration);
        }
    }

    /*
     * Arrays belong to the snapshot and can be modified by its caller.
     * occurrenceWords is sorted lexically; occurrenceIndexes[i] contains all
     * token indexes for occurrenceWords[i], in increasing order.
     */
    public static final class Snapshot {
        public final String[] vocabulary;
        public final String[] tokens;
        public final String[] occurrenceWords;
        public final int[][] occurrenceIndexes;
        public final long vocabularyRevision;
        public final long contextGeneration;

        private Snapshot(
                Vocabulary vocabulary,
                Context context,
                long vocabularyRevision,
                long contextGeneration) {
            this.vocabulary = copyStrings(vocabulary.words);
            this.tokens = copyStrings(context.tokens);
            this.occurrenceWords = copyStrings(context.occurrenceWords);
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

    private static Context prepareContext(String sentence) {
        int tokenCount = 0;
        int position = 0;
        while (position < sentence.length()) {
            if (isAsciiLetter(sentence.charAt(position))) {
                tokenCount++;
                do {
                    position++;
                } while (position < sentence.length()
                        && isAsciiLetter(sentence.charAt(position)));
            } else {
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
            do {
                position++;
            } while (position < sentence.length()
                    && isAsciiLetter(sentence.charAt(position)));

            char[] letters = new char[position - start];
            for (int i = 0; i < letters.length; i++) {
                letters[i] = lowercaseAscii(sentence.charAt(start + i));
            }
            tokens[tokenIndex++] = new String(letters);
        }

        String[] sortedTokens = copyStrings(tokens);
        sort(sortedTokens, false);

        int uniqueCount = 0;
        for (int i = 0; i < sortedTokens.length; i++) {
            if (i == 0 || !sortedTokens[i].equals(sortedTokens[i - 1])) {
                uniqueCount++;
            }
        }

        String[] occurrenceWords = new String[uniqueCount];
        int[] counts = new int[uniqueCount];
        int wordIndex = -1;
        for (int i = 0; i < sortedTokens.length; i++) {
            if (i == 0 || !sortedTokens[i].equals(sortedTokens[i - 1])) {
                wordIndex++;
                occurrenceWords[wordIndex] = sortedTokens[i];
            }
            counts[wordIndex]++;
        }

        int[][] occurrenceIndexes = new int[uniqueCount][];
        for (int i = 0; i < uniqueCount; i++) {
            occurrenceIndexes[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < tokens.length; i++) {
            int index = binarySearch(occurrenceWords, tokens[i]);
            occurrenceIndexes[index][counts[index]++] = i;
        }

        return new Context(tokens, occurrenceWords, occurrenceIndexes);
    }

    private static String normalizeLetters(String value, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.length() == 0)) {
            throw new IllegalArgumentException("Invalid ASCII-letter string");
        }

        char[] normalized = new char[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c)) {
                throw new IllegalArgumentException("Invalid ASCII-letter string");
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

    private static String[] copyStrings(String[] source) {
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
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

    private static void sort(String[] values, boolean longerFirst) {
        if (values.length < 2) {
            return;
        }
        String[] work = new String[values.length];
        mergeSort(values, work, 0, values.length, longerFirst);
    }

    private static void mergeSort(
            String[] values, String[] work, int start, int end,
            boolean longerFirst) {
        if (end - start < 2) {
            return;
        }
        int middle = start + (end - start) / 2;
        mergeSort(values, work, start, middle, longerFirst);
        mergeSort(values, work, middle, end, longerFirst);

        int left = start;
        int right = middle;
        int output = start;
        while (left < middle && right < end) {
            if (compare(values[left], values[right], longerFirst) <= 0) {
                work[output++] = values[left++];
            } else {
                work[output++] = values[right++];
            }
        }
        while (left < middle) {
            work[output++] = values[left++];
        }
        while (right < end) {
            work[output++] = values[right++];
        }
        for (int i = start; i < end; i++) {
            values[i] = work[i];
        }
    }

    private static int compare(String left, String right, boolean longerFirst) {
        if (longerFirst && left.length() != right.length()) {
            return left.length() > right.length() ? -1 : 1;
        }
        return left.compareTo(right);
    }
}
