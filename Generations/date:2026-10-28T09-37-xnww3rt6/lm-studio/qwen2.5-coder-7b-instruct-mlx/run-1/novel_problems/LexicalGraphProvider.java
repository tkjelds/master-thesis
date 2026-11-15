@LexicalGraphProvider.ThreadSafe
public class LexicalGraphProvider {
    @interface ThreadSafe {}

    private static final int MAX_REVISIONS = 1 << 30; // Prevent counter overflow
    private static final int MAX_TOKENS = 1 << 30; // Limit token length for simplicity

    private String[] vocabulary;
    private int vocabularyRevision;
    private TokenIndex context;
    private int contextGeneration;

    public LexicalGraphProvider() {
        replaceVocabulary(new String[0]);
        setContext("");
    }

    public synchronized void replaceVocabulary(String[] words) {
        if (words == null || words.length > MAX_TOKENS) {
            throw new IllegalArgumentException("Invalid vocabulary");
        }

        String[] normalizedWords = normalizeAndDeduplicate(words);
        this.vocabulary = normalizedWords;
        this.vocabularyRevision++;
    }

    public synchronized void setContext(String sentence) {
        if (sentence == null || sentence.length() > MAX_TOKENS) {
            throw new IllegalArgumentException("Invalid context");
        }

        TokenIndex newContext = new TokenIndex(sentence);
        this.context = newContext;
        this.contextGeneration++;
    }

    public synchronized Session openSession() {
        String[] sessionVocabulary = vocabulary;
        TokenIndex sessionContext = context;
        int sessionVocabRev = vocabularyRevision;
        int sessionContextGen = contextGeneration;

        return new Session(sessionVocabulary, sessionContext, sessionVocabRev, sessionContextGen);
    }

    public synchronized String[] snapshot() {
        return vocabulary.clone();
    }

    private static String[] normalizeAndDeduplicate(String[] words) {
        if (words == null || words.length > MAX_TOKENS) {
            throw new IllegalArgumentException("Invalid vocabulary");
        }

        boolean[] seen = new boolean[MAX_TOKENS];
        StringBuilder builder = new StringBuilder();
        int count = 0;

        for (String word : words) {
            if (!word.isEmpty() && seen[builder.length()]) continue;

            builder.append(word.toLowerCase()).append(' ');
            count++;
        }

        if (count > MAX_TOKENS) {
            throw new IllegalArgumentException("Invalid vocabulary");
        }

        String[] result = new String[count];
        while (count-- > 0) {
            int index = builder.indexOf(' ');
            result[count] = builder.substring(0, index);
            builder.delete(0, index + 1);
        }

        return result;
    }
}

@TokenIndex.ThreadSafe
class TokenIndex {
    @interface ThreadSafe {}

    private String[] tokens;
    private int[] occurrences;

    public TokenIndex(String sentence) {
        StringBuilder builder = new StringBuilder();
        boolean inToken = false;

        for (char ch : sentence.toCharArray()) {
            if (Character.isLetter(ch)) {
                if (!inToken) {
                    inToken = true;
                    builder.setLength(0);
                }
                builder.append(Character.toLowerCase(ch));
            } else if (inToken) {
                inToken = false;
                addToken(builder.toString());
            }
        }

        if (inToken) {
            addToken(builder.toString());
        }
    }

    private synchronized void addToken(String token) {
        for (int i = 0; i < tokens.length; i++) {
            if (token.equals(tokens[i])) {
                occurrences[i]++;
                return;
            }
        }

        int[] newOccurrences = new int[tokens.length + 1];
        System.arraycopy(occurrences, 0, newOccurrences, 0, occurrences.length);
        newOccurrences[occurrences.length] = 1;

        String[] newTokens = new String[tokens.length + 1];
        System.arraycopy(tokens, 0, newTokens, 0, tokens.length);
        newTokens[tokens.length] = token;

        this.tokens = newTokens;
        this.occurrences = newOccurrences;
    }

    public synchronized int[] find(String prefix) {
        TreeSet<String> matches = new TreeSet<>();

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.toLowerCase().startsWith(prefix.toLowerCase())) {
                matches.add(token);
            }
        }

        int[] result = new int[matches.size()];
        int i = 0;
        for (String match : matches) {
            result[i++] = occurrences[tokens.indexOf(match)];
        }

        return result;
    }
}

@Session.ThreadSafe
class Session {
    @interface ThreadSafe {}

    private final String[] vocabulary;
    private final TokenIndex context;
    private final int vocabularyRevision;
    private final int contextGeneration;

    public Session(String[] vocabulary, TokenIndex context, int vocabularyRevision, int contextGeneration) {
        this.vocabulary = vocabulary;
        this.context = context;
        this.vocabularyRevision = vocabularyRevision;
        this.contextGeneration = contextGeneration;
    }

    public int[] findMatches(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("Invalid prefix");
        }

        return context.find(prefix);
    }

    public int[] dependencies(int tokenIndex) {
        if (tokenIndex < 0 || tokenIndex >= context.tokens.length) {
            throw new IllegalArgumentException("Invalid token index");
        }

        Set<Integer> indices = new TreeSet<>();

        for (int i = 0; i < context.tokens.length; i++) {
            if (i > tokenIndex && vocabularyRevision == context.occurrences[i]) {
                indices.add(i);
            }
        }

        int[] result = new int[indices.size()];
        int i = 0;
        for (int index : indices) {
            result[i++] = context.occurrences[index];
        }

        return result;
    }

    public String[] snapshot() {
        return vocabulary.clone();
    }
}
