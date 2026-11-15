public final class AdaptiveTrustVault {
    public interface Clock {
        long now();
    }

 public static final class Metrics {
     public final long truePositives;
     public final long falsePositives;
     public final long falseNegatives;
     public final long trueNegatives;
     public final double f1;
     public final double effectiveTTL;
     public final int storedEntryCount;

     private Metrics(long truePositives, long falsePositives,
                     long falseNegatives, long trueNegatives,
                     double f1, double effectiveTTL, int storedEntryCount) {
         this.truePositives = truePositives;
         this.falsePositives = falsePositives;
         this.falseNegatives = falseNegatives;
         this.trueNegatives = trueNegatives;
         this.f1 = f1;
         this.effectiveTTL = effectiveTTL;
         this.storedEntryCount = storedEntryCount;
     }

     public long getTruePositives() {
         return truePositives;
     }

     public long getFalsePositives() {
         return falsePositives;
     }

     public long getFalseNegatives() {
         return falseNegatives;
     }

     public long getTrueNegatives() {
         return trueNegatives;
     }

     public double getF1() {
         return f1;
     }

     public double getEffectiveTTL() {
         return effectiveTTL;
     }

     public int getStoredEntryCount() {
         return storedEntryCount;
     }
 }

 private static final class Entry {
     private final String key;
     private final String credential;
     private final byte[] payload;
     private final long depositedAt;

     private Entry(String key, String credential, byte[] payload, long depositedAt) {
         this.key = key;
         this.credential = credential;
         this.payload = payload;
         this.depositedAt = depositedAt;
     }
 }

 private final long baseTTL;
 private final Clock clock;
 private Entry[] entries;
 private int entryCount;
 private long tp;
 private long fp;
 private long fn;
 private long tn;

 public AdaptiveTrustVault(long baseTTL, Clock clock) {
     if (baseTTL <= 0) {
         throw new IllegalArgumentException("baseTTL must be positive");
     }
     if (clock == null) {
         throw new NullPointerException("clock");
     }

     this.baseTTL = baseTTL;
     this.clock = clock;
     this.entries = new Entry[16];
 }

 public void deposit(String key, String credential, byte[] payload) {
     if (key == null) {
         throw new NullPointerException("key");
     }
     if (credential == null) {
         throw new NullPointerException("credential");
     }
     if (payload == null) {
         throw new NullPointerException("payload");
     }
     if (credential.length() == 0) {
         throw new IllegalArgumentException("credential must not be empty");
     }

     byte[] payloadCopy = payload.clone();

     synchronized (this) {
         long now = clock.now();
         int index = findEntryIndex(key);

         if (index >= 0) {
             entries[index] = new Entry(key, credential, payloadCopy, now);
             return;
         }

         if (entryCount == entries.length) {
             if (entries.length > Integer.MAX_VALUE / 2) {
                 throw new IllegalStateException("entry capacity exhausted");
             }
             Entry[] expanded = new Entry[entries.length * 2];
             for (int i = 0; i < entries.length; i++) {
                 expanded[i] = entries[i];
             }
             entries = expanded;
         }

         entries[entryCount++] = new Entry(key, credential, payloadCopy, now);
     }
 }

 public byte[] attemptRetrieve(String key, String credential) {
     if (key == null) {
         throw new NullPointerException("key");
     }
     if (credential == null) {
         throw new NullPointerException("credential");
     }

     synchronized (this) {
         long now = clock.now();
         int index = findEntryIndex(key);
         Entry entry = index >= 0 ? entries[index] : null;
         double ttlBeforeAttempt = effectiveTTL(tp, fp, fn);
         boolean aliveBeforeAttempt =
                 entry != null && isAlive(entry, now, ttlBeforeAttempt);
         boolean credentialMatches =
                 aliveBeforeAttempt && credential.equals(entry.credential);

         if (aliveBeforeAttempt) {
             if (credentialMatches) {
                 if (tp == Long.MAX_VALUE) {
                     throw new IllegalStateException("true-positive counter exhausted");
                 }
             } else if (fn == Long.MAX_VALUE) {
                 throw new IllegalStateException("false-negative counter exhausted");
             }
         } else if (credential.length() != 0) {
             if (fp == Long.MAX_VALUE) {
                 throw new IllegalStateException("false-positive counter exhausted");
             }
         } else if (tn == Long.MAX_VALUE) {
             throw new IllegalStateException("true-negative counter exhausted");
         }

         if (aliveBeforeAttempt) {
             if (credentialMatches) {
                 tp++;
             } else {
                 fn++;
             }
         } else if (credential.length() != 0) {
             fp++;
         } else {
             tn++;
         }

         double ttlAfterAttempt = effectiveTTL(tp, fp, fn);
         boolean aliveAfterAttempt =
                 aliveBeforeAttempt && isAlive(entry, now, ttlAfterAttempt);

         if (entry != null && (!aliveBeforeAttempt || !aliveAfterAttempt)) {
             removeEntryAt(index);
         }

         if (credentialMatches && aliveAfterAttempt) {
             return entry.payload.clone();
         }
         return null;
     }
 }

 public void cleanup() {
     synchronized (this) {
         long now = clock.now();
         double ttl = effectiveTTL(tp, fp, fn);
         int index = 0;

         while (index < entryCount) {
             if (!isAlive(entries[index], now, ttl)) {
                 removeEntryAt(index);
             } else {
                 index++;
             }
         }
     }
 }

 public Metrics snapshotMetrics() {
     synchronized (this) {
         double f1 = f1Score(tp, fp, fn);
         return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, entryCount);
     }
 }

 private int findEntryIndex(String key) {
     for (int i = 0; i < entryCount; i++) {
         if (entries[i].key.equals(key)) {
             return i;
         }
     }
     return -1;
 }

 private void removeEntryAt(int index) {
     for (int i = index; i < entryCount - 1; i++) {
         entries[i] = entries[i + 1];
     }
     entries[--entryCount] = null;
 }

 private static boolean isAlive(Entry entry, long now, double ttl) {
     return (double) (now - entry.depositedAt) < ttl;
 }

 private double effectiveTTL(long truePositives, long falsePositives,
                             long falseNegatives) {
     return baseTTL * f1Score(truePositives, falsePositives, falseNegatives);
 }

 private static double f1Score(long truePositives, long falsePositives,
                               long falseNegatives) {
     double numerator = 2.0 * (double) truePositives;
     double denominator = numerator
             + (double) falsePositives
             + (double) falseNegatives;
     return denominator == 0.0 ? 1.0 : numerator / denominator;
 }

}
