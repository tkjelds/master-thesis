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
        public final long storedEntries;

        private Metrics(long tp, long fp, long fn, long tn, double f1,
                        double effectiveTTL, long storedEntries) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTTL = effectiveTTL;
            this.storedEntries = storedEntries;
        }
    }

    private static final class Entry {
        private final String key;
        private final String credential;
        private final byte[] payload;
        private final long depositedAt;
        private Entry next;

        private Entry(String key, String credential, byte[] payload, long depositedAt) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
        }
    }

    private final Object monitor = new Object();
    private final long baseTTL;
    private final Clock clock;
    private Entry entries;
    private long tp;
    private long fp;
    private long fn;
    private long tn;
    private long storedEntries;

    public AdaptiveTrustVault(long baseTTL, Clock clock) {
        if (baseTTL <= 0) {
            throw new IllegalArgumentException("baseTTL must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTTL = baseTTL;
        this.clock = clock;
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

        byte[] retainedPayload = payload.clone();

        synchronized (monitor) {
            long now = clock.now();
            Entry previous = null;
            Entry current = entries;

            while (current != null && !current.key.equals(key)) {
                previous = current;
                current = current.next;
            }

            Entry replacement = new Entry(key, credential, retainedPayload, now);
            if (current != null) {
                replacement.next = current.next;
                if (previous == null) {
                    entries = replacement;
                } else {
                    previous.next = replacement;
                }
            } else {
                if (storedEntries == Long.MAX_VALUE) {
                    throw new ArithmeticException("stored-entry count exhausted");
                }
                replacement.next = entries;
                entries = replacement;
                storedEntries++;
            }
        }
    }

    public byte[] attemptRetrieve(String key, String credential) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        if (credential == null) {
            throw new NullPointerException("credential");
        }

        synchronized (monitor) {
            long now = clock.now();
            Entry entry = findEntry(key);
            double ttlBefore = effectiveTTL();
            boolean aliveBefore = entry != null && isAlive(entry, now, ttlBefore);
            boolean matched = aliveBefore && entry.credential.equals(credential);

            if (aliveBefore) {
                if (matched) {
                    ensureCounterAvailable(tp, "TP");
                } else {
                    ensureCounterAvailable(fn, "FN");
                }
            } else if (credential.length() != 0) {
                ensureCounterAvailable(fp, "FP");
            } else {
                ensureCounterAvailable(tn, "TN");
            }

            if (aliveBefore) {
                if (matched) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (credential.length() != 0) {
                fp++;
                if (entry != null) {
                    removeEntry(entry);
                }
            } else {
                tn++;
                if (entry != null) {
                    removeEntry(entry);
                }
            }

            double ttlAfter = effectiveTTL();
            boolean aliveAfter = aliveBefore && isAlive(entry, now, ttlAfter);
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
        synchronized (monitor) {
            long now = clock.now();
            double ttl = effectiveTTL();
            Entry previous = null;
            Entry current = entries;

            while (current != null) {
                Entry next = current.next;
                if (!isAlive(current, now, ttl)) {
                    if (previous == null) {
                        entries = next;
                    } else {
                        previous.next = next;
                    }
                    storedEntries--;
                } else {
                    previous = current;
                }
                current = next;
            }
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            double f1 = f1();
            return new Metrics(tp, fp, fn, tn, f1, baseTTL * f1, storedEntries);
        }
    }

    private Entry findEntry(String key) {
        Entry current = entries;
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
        Entry current = entries;

        while (current != null && current != target) {
            previous = current;
            current = current.next;
        }

        if (current != null) {
            if (previous == null) {
                entries = current.next;
            } else {
                previous.next = current.next;
            }
            storedEntries--;
        }
    }

    private boolean isAlive(Entry entry, long now, double ttl) {
        return (double) (now - entry.depositedAt) < ttl;
    }

    private double effectiveTTL() {
        return baseTTL * f1();
    }

    private double f1() {
        if (tp == 0 && fp == 0 && fn == 0) {
            return 1.0;
        }
        double truePositives = (double) tp;
        return (2.0 * truePositives)
                / (2.0 * truePositives + (double) fp + (double) fn);
    }

    private void ensureCounterAvailable(long value, String name) {
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException(name + " counter exhausted");
        }
    }
}
