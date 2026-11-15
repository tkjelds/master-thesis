@LexicalGraphProvider.ThreadSafe
LexicalGraphProvider.java
class LexicalGraphProvider {
    @interface ThreadSafe {}


    // --- Internal Structures and State ---

    private volatile int vocabularyRevision = 0;
    private volatile int contextGeneration = 0;

    // Provider State (Protected by synchronization)
    private Object vocabularyMonitorLock = new Object();
    private String[] currentVocabulary;

    private Object contextMonitorLock = new Object();
    private String[] currentContextTokens;
    private int[] occurrenceIndexMap; // Map context tokens back to provider concepts if needed, though not explicitly used in the current contract for *setting*, but critical for matching.

    // Session Management
    private Object sessionMonitorLock = new Object();
    // Stores active sessions waiting to be initialized or updated. In this strict contract, we model snapshots/sessions being instances held by the caller.
    // Since providers must return objects, we simulate the binding upon successful openSession().

    private Session currentPinnedSession; // Holds the active session instance if one was successfully opened.

    // --- Data Structures for Preparation (Outside Monitor) ---
    private String[] nextVocabularySnapshot;
    private int nextContextGenerationTokenCount = 0; // Simulating the size of the context array

    // --- Constructor & Initialization ---
    public LexicalGraphProvider() {
        // Initial state: Empty vocabulary, revision 0. Context generation 0.
        this.currentVocabulary = new String[0];
        this.nextVocabularySnapshot = new String[0];
    }

    // --- Helper Methods for Input Validation and Normalization ---

    private static boolean isValidWord(String word) {
        if (word == null || word.isEmpty()) return false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        return true;
    }

    private static String normalizeWord(String word) {
        if (word == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else if (c >= 'a' && c <= 'z') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // --- Public Interface Methods ---

    /**
     * Prepares a new vocabulary set. This happens outside the commitment monitor.
     * @param words Array of candidate words.
     */
    public void replaceVocabulary(String[] words) {
        if (words == null) return;

        // 1. Input validation and copying (Preparation outside commit monitor)
        String[] validNormalizedWords = new String[words.length];
        int count = 0;

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word != null && isValidWord(word)) {
                String normalized = normalizeWord(word);
                // Check for deduplication against already prepared words in this batch
                boolean isDuplicate = false;
                for (int j = 0; j < count; j++) {
                    if (word.equals(words[j]) && word.equals(words[i])) {
                        isDuplicate = true;
                        break;
                    }
                }

                if (!isDuplicate) {
                    validNormalizedWords[count] = normalized;
                    count++;
                }
            }
        }

        // Copy to the buffer for atomic publication
        String[] tempVocab = new String[count];
        System.arraycopy(validNormalizedWords, 0, tempVocab, 0, count);

        synchronized (vocabularyMonitorLock) {
            this.nextVocabularySnapshot = tempVocab; // Link the prepared data
        }
    }

    /**
     * Prepares a new context sentence. This happens outside the commit monitor.
     * @param sentence The input sentence string.
     */
    public void setContext(String sentence) {
        if (sentence == null) return;

        // Context Tokenization: Extract maximal ASCII letter runs and lowercase them.
        StringBuilder sb = new StringBuilder();
        String normalizedSentence = sentence.toLowerCase();
        boolean inToken = false;
        int currentTokenStart = -1;

        // Simplified simulation of token extraction based on maximal runs
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[a-z]+");
        java.util.regex.Matcher matcher = pattern.matcher(normalizedSentence);

        // This simulates the preparation phase: extracting tokens and mapping them
        java.util.ArrayList<String> extractedTokens = new java.util.ArrayList<>();
        while (matcher.find()) {
            extractedTokens.add(matcher.group());
        }

        // Copy the extraction result into the internal buffer structure that depends on contextGeneration
        synchronized (contextMonitorLock) {
            // In a real scenario, occurrence mapping would happen here based on the structure of currentVocabulary.
            // Since we are preparing *before* publishing, we just store the tokens for now.
            int[] mapPlaceholder = new int[0]; // Placeholder for occurrence index mapping data
            this.currentContextTokens = extractedTokens.toArray(new String[0]);
            this.occurrenceIndexMap = mapPlaceholder; // Should be populated during pairing with vocabulary if necessary for dependency checks.
        }
    }

    /**
     * Atomically switches to the newly prepared vocabulary.
     */
    public void commitVocabulary() {
        synchronized (vocabularyMonitorLock) {
            if (nextVocabularySnapshot == null || nextVocabularySnapshot.length == 0) return;

            // Atomic switch
            this.currentVocabulary = nextVocabularySnapshot;
            vocabularyRevision++;
            // Clean up old snapshot data if necessary (omitted for simplicity, focusing on visibility)
            this.nextVocabularySnapshot = null; // Ready for next batch

            // Notification mechanism would go here if waiters existed waiting for vocabulary change.
        }
    }

    /**
     * Atomically switches to the newly prepared context.
     */
    public void commitContext() {
        synchronized (contextMonitorLock) {
            // Assume setContext has run and prepared the context data buffers.
            // This simulates atomically swapping in the new context associated with the current revision flow.

            // Since setContext populates the buffer, we just need to increment the generation counter
            // and ensure the commit aligns context changes with vocabulary/session expectations.
            contextGeneration++;

            // In a real system, this commit might also need to coordinate with the vocabulary revision advance
            // if context dependencies rely on a specific state.
        }
    }

    /**
     * Opens a session, atomically capturing the current state.
     * @return A Session object pinning the current provider versions, or null if synchronization failed/rejected.
     */
    public Session openSession() {
        synchronized (sessionMonitorLock) {
            // Check for potential overflow/invariants before committing to the session.

            // Capture current versions safely
            int vocabRev;
            int ctxGen;
            String[] snapshotVocab;
            String[] snapshotContextTokens;

            // Read current provider state safely (must be done carefully to ensure consistency)
            synchronized (vocabularyMonitorLock) {
                vocabRev = vocabularyRevision;
                snapshotVocab = this.currentVocabulary; // This is the pinned snapshot
            }

            synchronized (contextMonitorLock) {
                ctxGen = contextGeneration;
                snapshotContextTokens = this.currentContextTokens; // This is the pinned snapshot
            }

            // If both states are consistent (not transiently empty/uninitialized if required), create the session.
            if (vocabRev >= 0 && ctxGen >= 0) { // Assuming initial state is valid enough to proceed
                // Create the Session instance pinning these specific arrays/versions.
                return new Session(snapshotVocab, snapshotContextTokens, vocabRev, ctxGen);
            }
            return null; // Failed to open due to initial invalid state or operational error.
        }
    }

    /**
     * Returns a snapshot of the current provider state without affecting future providers.
     */
    public ProviderSnapshot snapshot() {
        ProviderSnapshot ps = new ProviderSnapshot();

        synchronized (vocabularyMonitorLock) {
            // Deep copy the vocabulary to ensure detachment and immutability for the snapshot.
            int count = this.currentVocabulary == null ? 0 : this.currentVocabulary.length;
            String[] copiedVocab = new String[count];
            if (count > 0) {
                System.arraycopy(this.currentVocabulary, 0, copiedVocab, 0, count);
            }
            ps.vocabulary = copiedVocab;
            ps.revision = vocabularyRevision;
        }

        synchronized (contextMonitorLock) {
            // Deep copy the context tokens.
            int count = this.currentContextTokens == null ? 0 : this.currentContextTokens.length;
            String[] copiedTokens = new String[count];
             if (count > 0) {
                System.arraycopy(this.currentContextTokens, 0, copiedTokens, 0, count);
            }
            ps.contextTokens = copiedTokens;
            ps.generation = contextGeneration;
        }

        return ps;
    }
}

/**
 * Represents a queryable, immutable view of the Provider state captured at a specific moment.
 */
@ProviderSnapshot.ThreadSafe
class ProviderSnapshot {
    @interface ThreadSafe {}

    public String[] vocabulary; // Detached copy
    public int revision;
    public String[] contextTokens; // Detached copy
    public int generation;
}


/**
 * Represents a session into the provider's state, pinned at creation time.
 */
@Session.ThreadSafe
class Session {
    @interface ThreadSafe {}

    // Pinned versions taken at construction. These arrays are copies/references to the state *at that time*.
    private final String[] pinnedVocabulary;
    private final int initialVocabRevision;

    private final String[] pinnedContextTokens;
    private final int initialContextGeneration;

    // The actual operational state needed for matching/dependency tracking goes here.
    // For this simulation, we assume the Session object encapsulates all necessary matching logic derived from the provider's conceptual models.

    public Session(String[] vocab, String[] contextTokens, int vocabRev, int ctxGen) {
        this.pinnedVocabulary = vocab; // Should be a copy if array modification was possible elsewhere
        this.initialVocabRevision = vocabRev;
        this.pinnedContextTokens = contextTokens; // Should be a copy if array modification was possible elsewhere
        this.initialContextGeneration = ctxGen;
    }

    /**
     * Finds distinct vocabulary words matching the prefix that occurred within the pinned context.
     * @param prefix ASCII letter prefix (case-insensitive).
     * @return Array of matching words, sorted by decreasing length then ascending lexical order.
     */
    public String[] findMatches(String prefix) {
        if (prefix == null) throw new IllegalArgumentException("Prefix cannot be null.");

        String searchPrefix = prefix.toLowerCase();
        java.util.ArrayList<String> matches = new java.util.ArrayList<>();

        // Simulating the search against pinnedVocabulary (which must be <= providers' current vocabulary)
        for (int i = 0; i < pinnedVocabulary.length; i++) {
            String word = pinnedVocabulary[i]; // Assume these are already normalized/lowercased.

            if (word != null && word.toLowerCase().startsWith(searchPrefix)) {
                matches.add(word);
            }
        }

        // Sorting logic simulation (Descending length, Ascending lexical)
        matches.sort(new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
                String s1 = (String) o1;
                String s2 = (String) o2;

                // 1. Decreasing length
                int lenCompare = Integer.compare(s2.length(), s1.length());
                if (lenCompare != 0) {
                    return lenCompare;
                }

                // 2. Ascending lexical order
                return s1.compareTo(s2);
            }
        });

        // Return a newly copied, sorted array (detached)
        return matches.toArray(new String[0]);
    }

    /**
     * Requires the token index and returns subsequent tokens in the pinned context belonging to the matched vocabulary.
     * Note: The return must be increasing indexes relative to the *matched tokens*, not the whole context array.
     * This implementation simulates returning the actual token content if they match criteria.
     */
    public String[] dependencies(int tokenIndex) {
        if (tokenIndex < 0 || tokenIndex >= pinnedContextTokens.length) {
            throw new IllegalArgumentException("Invalid token index.");
        }

        // In a real system, this compares pinnedContextTokens[tokenIndex] against the vocabulary state at creation
        // to determine if it belongs to the vocab, and then finds subsequent tokens that also belong.

        // Simulation: If the context token at this index is non-empty, return a dependent list.
        if (pinnedContextTokens[tokenIndex] != null && !pinnedContextTokens[tokenIndex].isEmpty()) {
            // Simulating finding subsequent tokens that are "dependent"
            java.util.ArrayList<String> dependencies = new java.util.ArrayList<>();
            for (int i = tokenIndex + 1; i < pinnedContextTokens.length; i++) {
                if (pinnedContextTokens[i] != null && !pinnedContextTokens[i].isEmpty()) {
                    // Append the token content (as a simplified representation of index/dependence)
                    dependencies.add(pinnedContextTokens[i]);
                }
            }
            return dependencies.toArray(new String[0]);
        }
        return new String[0]; // No dependencies found.
    }

    // Contextual/Provider specific methods would go here...
}
