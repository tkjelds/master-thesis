@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private String[] vocabulary = new String[0];
    private int vocabularyRevision = 0;
    private String context = "";
    private Token[] tokens;
    private int tokenIndex[][];
    private int contextGeneration = 0;

    public synchronized void replaceVocabulary(String[] words) {
        if (words == null) throw new IllegalArgumentException("Null vocabulary");
        for (String word : words) {
            if (word == null || !word.matches("[a-zA-Z]+")) throw new IllegalArgumentException("Invalid word");
        }
        words = vocabularyDedupAndNormalize(words);
        int newVocabRevision = vocabularyRevision + 1;
        vocabulary = words;
        vocabularyRevision = newVocabRevision;
    }

    public synchronized void setContext(String sentence) {
        if (sentence == null) throw new IllegalArgumentException("Null context");
        String[] tokenArray = tokenize(sentence);
        Token[] newTokens = toTokenArray(tokenArray);
        int[][] newTokenIndex = new int[tokenArray.length][];
        for (int i = 0; i < tokenArray.length; ++i) {
            newTokenIndex[i] = findIndices(tokenArray, i);
        }
        int newContextGeneration = contextGeneration + 1;
        context = sentence;
        tokens = newTokens;
        tokenIndex = newTokenIndex;
        contextGeneration = newContextGeneration;
    }

    public Session openSession() {
        synchronized (this) {
            return new Session(vocabulary, tokens, tokenIndex, vocabularyRevision, contextGeneration);
        }
    }

    public String[] snapshot() {
        synchronized (this) {
            return vocabulary.clone();
        }
    }

    private String[] vocabularyDedupAndNormalize(String[] words) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String word : words) {
            set.add(word.toLowerCase());
        }
        return set.toArray(new String[0]);
    }

    private String[] tokenize(String sentence) {
        java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
        int start = -1;
        for (int i = 0; i < sentence.length(); ++i) {
            char c = sentence.charAt(i);
            if (Character.isLetter(c)) {
                if (start == -1) start = i;
            } else {
                if (start != -1) {
                    tokens.add(sentence.substring(start, i).toLowerCase());
                    start = -1;
                }
            }
        }
        if (start != -1) tokens.add(sentence.substring(start).toLowerCase());
        return tokens.toArray(new String[0]);
    }

    private Token[] toTokenArray(String[] tokenArray) {
        Token[] tokens = new Token[tokenArray.length];
        for (int i = 0; i < tokenArray.length; ++i) {
            tokens[i] = new Token(tokenArray[i], i);
        }
        return tokens;
    }

    private int[] findIndices(String[] tokenArray, int start) {
        java.util.ArrayList<Integer> indices = new java.util.ArrayList<>();
        for (int i = start; i < tokenArray.length; ++i) {
            if (tokenArray[i].equals(tokenArray[start])) indices.add(i);
        }
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    public static class Session {
        private final String[] vocabulary;
        private final Token[] tokens;
        private final int[][] tokenIndex;
        private final int vocabularyRevision;
        private final int contextGeneration;

        public Session(String[] vocabulary, Token[] tokens, int[][] tokenIndex, int vocabularyRevision, int contextGeneration) {
            this.vocabulary = vocabulary;
            this.tokens = tokens;
            this.tokenIndex = tokenIndex;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public String[] findMatches(String prefix) {
            java.util.Set<String> matches = new java.util.TreeSet<>((a, b) -> {
                int lenA = a.length();
                int lenB = b.length();
                if (lenA != lenB) return lenB - lenA;
                return a.compareTo(b);
            });
            for (String word : vocabulary) {
                if (word.toLowerCase().startsWith(prefix.toLowerCase())) matches.add(word);
            }
            return matches.toArray(new String[0]);
        }

        public int[] dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= this.tokenIndex.length) throw new IllegalArgumentException("Invalid token index");
            return tokenIndex.clone();
        }

        public String[] snapshot() {
            return vocabulary.clone();
        }
    }
}
