@AdaptiveTrustVault.ThreadSafe
public final class AdaptiveTrustVault {
    @interface ThreadSafe {}

    public interface Clock {
        long now();
    }

    public static final class MetricsSnapshot {
        public final long tp;
        public final long fp;
        public final long fn;
        public final long tn;
        public final double f1;
        public final double effectiveTtl;
        public final int storedEntryCount;

        private MetricsSnapshot(long tp, long fp, long fn, long tn,
                                double f1, double effectiveTtl, int storedEntryCount) {
            this.tp = tp;
            this.fp = fp;
            this.fn = fn;
            this.tn = tn;
            this.f1 = f1;
            this.effectiveTtl = effectiveTtl;
            this.storedEntryCount = storedEntryCount;
        }
    }

    private static final class Entry {
        final String key;
        final String credential;
        final byte[] payload;
        final long depositedAt;

        Entry(String key, String credential, byte[] payload, long depositedAt) {
            this.key = key;
            this.credential = credential;
            this.payload = payload;
            this.depositedAt = depositedAt;
        }
    }

    private final long baseTtl;
    private final Clock clock;
    private Entry[] entries = new Entry[8];
    private int size;

    private long tp;
    private long fp;
    private long fn;
    private long tn;

    public AdaptiveTrustVault(long baseTtl, Clock clock) {
        if (baseTtl <= 0) {
            throw new IllegalArgumentException("baseTtl must be positive");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        this.baseTtl = baseTtl;
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

        byte[] payloadCopy = copyPayload(payload);

        synchronized (this) {
            long depositedAt = clock.now();
            int index = findIndex(key);
            Entry replacement = new Entry(key, credential, payloadCopy, depositedAt);

            if (index >= 0) {
                entries[index] = replacement;
                return;
            }

            if (size == entries.length) {
                if (entries.length >= Integer.MAX_VALUE / 2) {
                    throw new IllegalStateException("vault capacity exhausted");
                }
                Entry[] expanded = new Entry[entries.length * 2];
                System.arraycopy(entries, 0, expanded, 0, size);
                entries = expanded;
            }

            entries[size++] = replacement;
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
            int index = findIndex(key);
            Entry entry = index >= 0 ? entries[index] : null;
            long age = entry == null ? 0L : now - entry.depositedAt;
            double ttlBeforeAttempt = effectiveTtl(currentF1());
            boolean aliveBeforeAttempt = entry != null && age < ttlBeforeAttempt;
            boolean credentialMatches = aliveBeforeAttempt
                    && entry.credential.equals(credential);

            if (aliveBeforeAttempt) {
                if (credentialMatches) {
                    if (tp == Long.MAX_VALUE) {
                        throw new ArithmeticException("TP counter exhausted");
                    }
                } else if (fn == Long.MAX_VALUE) {
                    throw new ArithmeticException("FN counter exhausted");
                }
            } else if (credential.length() != 0) {
                if (fp == Long.MAX_VALUE) {
                    throw new ArithmeticException("FP counter exhausted");
                }
            } else if (tn == Long.MAX_VALUE) {
                throw new ArithmeticException("TN counter exhausted");
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

            if (entry == null) {
                return null;
            }

            if (!aliveBeforeAttempt) {
                removeAt(index);
                return null;
            }

            double ttlAfterAttempt = effectiveTtl(currentF1());
            boolean aliveAfterAttempt = age < ttlAfterAttempt;
            if (!aliveAfterAttempt) {
                removeAt(index);
            }

            if (credentialMatches && aliveAfterAttempt) {
                return copyPayload(entry.payload);
            }
            return null;
        }
    }

    public void cleanup() {
        synchronized (this) {
            long now = clock.now();
            double ttl = effectiveTtl(currentF1());

            int index = 0;
            while (index < size) {
                Entry entry = entries[index];
                long age = now - entry.depositedAt;
                if (age >= ttl) {
                    removeAt(index);
                } else {
                    index++;
                }
            }
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (this) {
            double f1 = currentF1();
            return new MetricsSnapshot(tp, fp, fn, tn, f1, effectiveTtl(f1), size);
        }
    }

    private int findIndex(String key) {
        for (int i = 0; i < size; i++) {
            if (entries[i].key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void removeAt(int index) {
        int elementsAfter = size - index - 1;
        if (elementsAfter > 0) {
            System.arraycopy(entries, index + 1, entries, index, elementsAfter);
        }
        entries[--size] = null;
    }

    private double currentF1() {
        double numerator = 2.0 * (double) tp;
        double denominator = numerator + (double) fp + (double) fn;
        return denominator == 0.0 ? 1.0 : numerator / denominator;
    }

    private double effectiveTtl(double f1) {
        return (double) baseTtl * f1;
    }

    private static byte[] copyPayload(byte[] payload) {
        byte[] copy = new byte[payload.length];
        System.arraycopy(payload, 0, copy, 0, payload.length);
        return copy;
    }
}
