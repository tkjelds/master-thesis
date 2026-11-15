public final class TemporalForexCache {
    public interface Clock {
        long now();
    }

 public static record Conversion(double value, long revision, long observationTime) {
 }

 public static record Entry(String currency, double rate, long expiration) {
 }

 public static final class Snapshot {
     private final Entry[] entries;
     private final long revision;
     private final long observationTime;

     private Snapshot(Entry[] entries, long revision, long observationTime) {
         this.entries = new Entry[entries.length];
         for (int i = 0; i < entries.length; i++) {
             this.entries[i] = entries[i];
         }
         this.revision = revision;
         this.observationTime = observationTime;
     }

     public Entry[] entries() {
         Entry[] copy = new Entry[entries.length];
         for (int i = 0; i < entries.length; i++) {
             copy[i] = entries[i];
         }
         return copy;
     }

     public long revision() {
         return revision;
     }

     public long observationTime() {
         return observationTime;
     }
 }

 private static final class Rate {
     private final String currency;
     private final double value;
     private final long expiration;

     private Rate(String currency, double value, long expiration) {
         this.currency = currency;
         this.value = value;
         this.expiration = expiration;
     }
 }

 private final Object monitor = new Object();
 private final Clock clock;
 private Rate[] rates = new Rate[0];
 private long revision;

 public TemporalForexCache(Clock clock) {
     if (clock == null) {
         throw new IllegalArgumentException("clock must not be null");
     }
     this.clock = clock;
 }

 public void updateRate(String currency, double rate, long ttl) {
     updateRatesBatch(new String[] { currency }, new double[] { rate }, ttl);
 }

 public void updateRatesBatch(String[] currencies, double[] values, long ttl) {
     if (currencies == null || values == null
             || currencies.length == 0 || currencies.length != values.length) {
         throw new IllegalArgumentException("currency and rate arrays must be nonempty and have
equal lengths");
     }
     if (ttl <= 0) {
         throw new IllegalArgumentException("ttl must be positive");
     }

     String[] copiedCurrencies = new String[currencies.length];
     double[] copiedValues = new double[values.length];
     for (int i = 0; i < currencies.length; i++) {
         copiedCurrencies[i] = currencies[i];
         copiedValues[i] = values[i];
     }

     for (int i = 0; i < copiedCurrencies.length; i++) {
         validateCurrency(copiedCurrencies[i]);
         validateRate(copiedValues[i]);
         for (int j = 0; j < i; j++) {
             if (copiedCurrencies[i].equals(copiedCurrencies[j])) {
                 throw new IllegalArgumentException("duplicate currency: " + copiedCurrencies[i]);
             }
         }
     }

     synchronized (monitor) {
         long observationTime = clock.now();
         if (ttl > Long.MAX_VALUE - observationTime) {
             throw new IllegalStateException("rate expiration would overflow");
         }
         if (revision == Long.MAX_VALUE) {
             throw new IllegalStateException("cache revision would overflow");
         }

         long expiration = observationTime + ttl;
         int additional = 0;
         for (int i = 0; i < copiedCurrencies.length; i++) {
             boolean exists = false;
             for (int j = 0; j < rates.length; j++) {
                 if (rates[j].currency.equals(copiedCurrencies[i])) {
                     exists = true;
                     break;
                 }
             }
             if (!exists) {
                 additional++;
             }
         }
         if (additional > Integer.MAX_VALUE - rates.length) {
             throw new IllegalStateException("cache is too large");
         }

         Rate[] replacement = new Rate[rates.length + additional];
         for (int i = 0; i < rates.length; i++) {
             replacement[i] = rates[i];
         }

         int next = rates.length;
         for (int i = 0; i < copiedCurrencies.length; i++) {
             int index = -1;
             for (int j = 0; j < next; j++) {
                 if (replacement[j].currency.equals(copiedCurrencies[i])) {
                     index = j;
                     break;
                 }
             }
             Rate updated = new Rate(copiedCurrencies[i], copiedValues[i], expiration);
             if (index >= 0) {
                 replacement[index] = updated;
             } else {
                 replacement[next++] = updated;
             }
         }

         rates = replacement;
         revision++;
     }
 }

 public Conversion convert(String from, String to, double amount) {
     validateCurrency(from);
     validateCurrency(to);
     if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0.0) {
         throw new IllegalArgumentException("amount must be finite and nonnegative");
     }

     double fromRate;
     double toRate;
     long capturedRevision;
     long observationTime;

     synchronized (monitor) {
         observationTime = clock.now();
         Rate fromEntry = findRate(from);
         Rate toEntry = findRate(to);
         if (fromEntry == null || fromEntry.expiration <= observationTime
                 || toEntry == null || toEntry.expiration <= observationTime) {
             throw new IllegalStateException("a required rate is missing or expired");
         }

         fromRate = fromEntry.value;
         toRate = toEntry.value;
         capturedRevision = revision;
     }

     return new Conversion(amount / fromRate * toRate, capturedRevision, observationTime);
 }

 public int prune() {
     synchronized (monitor) {
         long observationTime = clock.now();
         int removed = 0;
         for (int i = 0; i < rates.length; i++) {
             if (rates[i].expiration <= observationTime) {
                 removed++;
             }
         }
         if (removed == 0) {
             return 0;
         }
         if (revision == Long.MAX_VALUE) {
             throw new IllegalStateException("cache revision would overflow");
         }

         Rate[] replacement = new Rate[rates.length - removed];
         int next = 0;
         for (int i = 0; i < rates.length; i++) {
             if (rates[i].expiration > observationTime) {
                 replacement[next++] = rates[i];
             }
         }

         rates = replacement;
         revision++;
         return removed;
     }
 }

 public Snapshot snapshot() {
     synchronized (monitor) {
         long observationTime = clock.now();
         Entry[] copiedEntries = new Entry[rates.length];
         for (int i = 0; i < rates.length; i++) {
             Rate rate = rates[i];
             copiedEntries[i] = new Entry(rate.currency, rate.value, rate.expiration);
         }
         return new Snapshot(copiedEntries, revision, observationTime);
     }
 }

 private Rate findRate(String currency) {
     for (int i = 0; i < rates.length; i++) {
         if (rates[i].currency.equals(currency)) {
             return rates[i];
         }
     }
     return null;
 }

 private static void validateCurrency(String currency) {
     if (currency == null || currency.length() == 0) {
         throw new IllegalArgumentException("currency must not be null or empty");
     }
 }

 private static void validateRate(double rate) {
     if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0.0) {
         throw new IllegalArgumentException("rate must be finite and positive");
     }
 }

}
