@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class Metrics {
        public final long tp;
        public final long fp;
        public final long fn;
        public final long tn;
        public final double f1;
        public final double effectiveTTL;
        public final long storedEntryCount;

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                        double effectiveTTL, long storedEntryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntryCount = storedEntryCount;
        }
    }

    private static final class Entry {
        final String key;
        String credential;
        byte[] payload;
        long depositTime;
        Entry next;

        Entry(String key, String credential, byte[] payload, long depositTime) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositTime = depositTime;
        }
    }

    private final Object lock = new Object();
    private final long baseTTL;
    private final Clock clock;
    private Entry head;

    private long tp;
    private long fp;
    private long fn;
    private long tn;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
    }

    public void deposit(String key, String credential, byte[] payload) {
        if (key == null || credential == null || payload == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }
        if (credential.length() == 0) {
            throw new IllegalArgumentException("deposit credential must not be empty");
        }

        byte[] payloadCopy = payload.clone();

        synchronized (lock) {
            long now = clock.now();
            if (now < 0) {
                throw new IllegalStateException("clock returned a negative time");
            }

            Entry entry = findEntry(key);
            if (entry == null) {
                Entry added = new Entry(key, credential, payloadCopy, now);
                added.next = head;
                head = added;
            } else {
                entry.credential = credential;
                entry.payload = payloadCopy;
                entry.depositTime = now;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null || credential == null) {
            throw new IllegalArgumentException("inputs must not be null");
        }

        synchronized (lock) {
            long now = clock.now();
            if (now < 0) {
                throw new IllegalStateException("clock returned a negative time");
            }

            double preAttemptTTL = effectiveTTL();
            Entry entry = findEntry(key);
            boolean found = entry != null;
            boolean aliveBefore = found && isAlive(entry, now, preAttemptTTL);
            boolean matched = aliveBefore && credential.equals(entry.credential);

            int classification;
            if (aliveBefore) {
                classification = matched ? 0 : 2;
            } else {
                classification = credential.length() == 0 ? 3 : 1;
            }

            checkCounterCanIncrement(classification);

            switch (classification) {
                case 0:
                    tp++;
                    break;
                case 1:
                    fp++;
                    break;
                case 2:
                    fn++;
                    break;
                default:
                    tn++;
                    break;
            }

            if (found && !aliveBefore) {
                removeEntry(entry);
            }

            double postAttemptTTL = effectiveTTL();
            boolean aliveAfter = aliveBefore && isAlive(entry, now, postAttemptTTL);
            if (aliveBefore && !aliveAfter) {
                removeEntry(entry);
            }

            if (matched && aliveAfter) {
                return entry.payload.clone();
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (lock) {
            long now = clock.now();
            if (now < 0) {
                throw new IllegalStateException("clock returned a negative time");
            }

            double ttl = effectiveTTL();
            Entry previous = null;
            Entry current = head;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now, ttl)) {
                    if (previous == null) {
                        head = next;
                    } else {
                        previous.next = next;
                    }
                    current.next = null;
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            double f1 = f1();
            double ttl = baseTTL * f1;
            long count = 0;
            Entry current = head;
            while (current != null) {
                count++;
                current = current.next;
            }
            return new Metrics(tp, fp, fn, tn, f1, ttl, count);
        }
    }

    private Entry findEntry(String key) {
        Entry current = head;
        while (current != null) {
            if (current.key.equals(key)) {
                return current;
            }
            current = current.next;
        }
        return null;
    }

    private void removeEntry(Entry target) {
        Entry previous = null;
        Entry current = head;
        while (current != null) {
            if (current == target) {
                if (previous == null) {
                    head = current.next;
                } else {
                    previous.next = current.next;
                }
                current.next = null;
                return;
            }
            previous = current;
            current = current.next;
        }
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        long age = now - entry.depositTime;
        return age >= 0 && (double) age < ttl;
    }

    private double f1() {
        double denominator = 2.0 * (double) tp + (double) fp + (double) fn;
        if (denominator == 0.0) {
            return 1.0;
        }
        return (2.0 * (double) tp) / denominator;
    }

    private double effectiveTTL() {
        return baseTTL * f1();
    }

    private void checkCounterCanIncrement(int classification) {
        long value;
        switch (classification) {
            case 0:
                value = tp;
                break;
            case 1:
                value = fp;
                break;
            case 2:
                value = fn;
                break;
            default:
                value = tn;
                break;
        }
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("metric counter exhausted");
        }
    }
}
