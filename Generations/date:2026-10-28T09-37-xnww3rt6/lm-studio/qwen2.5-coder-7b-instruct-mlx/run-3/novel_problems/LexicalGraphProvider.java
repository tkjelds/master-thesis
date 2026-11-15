```java
class LexicalGraphProvider {
    private String[] vocabulary;
    private int vocabularyRevision;
    private String context;
    private int contextGeneration;
    private TokenIndex tokenIndex;

    public LexicalGraphProvider() {
        vocabulary = new String[0];
        vocabularyRevision = 0;
        context = "";
        contextGeneration = 0;
        tokenIndex = new TokenIndex();
    }

    public synchronized void replaceVocabulary(String[] words) {
        if (words == null || words.length == 0) throw new IllegalArgumentException("Invalid vocabulary");

        String[] normalizedWords = new String[words.length];
        int count = 0;
        for (String word : words) {
            if (!isValidWord(word)) continue;
            normalizedWords[count++] = word.toLowerCase();
        }

        String[] newVocabulary = new String[count];
        System.arraycopy(normalizedWords, 0, newVocabulary, 0, count);

        vocabulary = newVocabulary;
        vocabularyRevision++;
    }

    public synchronized void setContext(String sentence) {
        if (sentence == null || sentence.isEmpty()) throw new IllegalArgumentException("Invalid context");

        String[] tokens = tokenize(sentence);
        int[] indices = new int[tokens.length];
        for (int i = 0, index = 0; i < tokens.length; i++) {
            indices[index++] = i;
        }

        tokenIndex = new TokenIndex(tokens, indices);
        contextGeneration++;
    }

    public synchronized Session openSession() {
        return new Session(vocabulary, context, vocabularyRevision, contextGeneration, tokenIndex);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(vocabulary, context, vocabularyRevision, contextGeneration);
    }

    public boolean isValidWord(String word) {
        for (char c : word.toCharArray()) {
            if (!Character.isLetter(c)) return false;
        }
        return true;
    }

    public String[] tokenize(String sentence) {
        StringBuilder token = new StringBuilder();
        List<String> tokensList = new ArrayList<>();

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (Character.isLetter(c)) {
                token.append(Character.toLowerCase(c));
            } else {
                if (token.length() > 0) {
                    tokensList.add(token.toString());
                    token.setLength(0);
                }
            }

            if (i == sentence.length() - 1 && token.length() > 0) {
                tokensList.add(token.toString());
            }
        }

        return tokensList.toArray(new String[0]);
    }

    static class TokenIndex {
        private final String[] tokens;
        private final int[] indices;

        public TokenIndex(String[] tokens, int[] indices) {
            this.tokens = tokens;
            this.indices = indices;
        }

        public String[] getTokens() {
            return tokens;
        }

        public int[] getIndices() {
            return indices;
        }
    }

    public static class Session {
        private final String[] vocabulary;
        private final String context;
        private final int vocabularyRevision;
        private final int contextGeneration;
        private final TokenIndex tokenIndex;

        public Session(String[] vocabulary, String context, int vocabularyRevision, int contextGeneration, TokenIndex tokenIndex) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
            this.tokenIndex = tokenIndex;
        }

        public List<String> findMatches(String prefix) {
            if (prefix == null) throw new IllegalArgumentException("Invalid prefix");

            List<String> matches = new ArrayList<>();
            String lowerPrefix = prefix.toLowerCase();

            for (String word : vocabulary) {
                if (word.startsWith(lowerPrefix)) {
                    matches.add(word);
                }
            }

            return new ArrayList<>(new TreeSet<>(Comparator.<String, String>() {
                @Override
                public int compare(String o1, String o2) {
                    if (o1.length() != o2.length()) return o2.length() - o1.length();
                    else return o1.compareTo(o2);
                }
            }.sorted(matches)));
        }

        public List<Integer> dependencies(int tokenIndex) {
            if (tokenIndex < 0 || tokenIndex >= this.tokenIndex.tokens.length) throw new IllegalArgumentException("Invalid token index");

            List<Integer> dependencies = new ArrayList<>();
            for (int i = 0; i < this.tokenIndex.tokens.length; i++) {
                if (this.tokenIndex.indices[i] > tokenIndex && this.vocabularyList().contains(this.tokenIndex.tokens[i])) {
                    dependencies.add(i);
                }
            }

            return dependencies;
        }

        public List<String> vocabularyList() {
            return Arrays.asList(vocabulary);
        }

        public String context() {
            return context;
        }

        public int vocabularyRevision() {
            return vocabularyRevision;
        }

        public int contextGeneration() {
            return contextGeneration;
        }
    }

    public static class Snapshot {
        private final String[] vocabulary;
        private final String context;
        private final int vocabularyRevision;
        private final int contextGeneration;

        public Snapshot(String[] vocabulary, String context, int vocabularyRevision, int contextGeneration) {
            this.vocabulary = vocabulary;
            this.context = context;
            this.vocabularyRevision = vocabularyRevision;
            this.contextGeneration = contextGeneration;
        }

        public List<String> vocabularyList() {
            return Arrays.asList(vocabulary);
        }

        public String context() {
            return context;
        }

        public int vocabularyRevision() {
            return vocabularyRevision;
        }

        public int contextGeneration() {
            return contextGeneration;
        }
    }
}
```
