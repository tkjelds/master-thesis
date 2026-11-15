import java.lang.*;

@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State { PENDING, INSTALLING, ACTIVE, FAILED }

    public static final class Snapshot {
        private final State state;
        private final long generation;
        private final String[] manifest;

        Snapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public State getState() { return state; }
        public long getGeneration() { return generation; }
        public String[] getManifest() { return manifest == null ? null : manifest.clone(); }
    }

    public static final class Metrics {
        private final int pending, installing, active, failed, total;

        Metrics(int pending, int installing, int active, int failed, int total) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = total;
        }

        public int getPending() { return pending; }
        public int getInstalling() { return installing; }
        public int getActive() { return active; }
        public int getFailed() { return failed; }
        public int getTotal() { return total; }
    }

    private static final int RUNNING = 0, SUCCESS = 1, FAILURE = 2, SUPERSEDED = 3;

    private static final class Attempt {
        final long generation;
        int status = RUNNING;
        String[] manifest;
        RuntimeException failure;

        Attempt(long generation) { this.generation = generation; }
    }

    private static final class Record {
        final String user;
        String[] config;
        State state = State.PENDING;
        long generation = 1;
        String[] manifest;
        RuntimeException failure;
        Attempt attempt;

        Record(String user, String[] config) {
            this.user = user;
            this.config = config;
        }
    }

    private static final class Entry {
        final String key;
        final Record rec;
        Entry next;

        Entry(String key, Record rec, Entry next) {
            this.key = key;
            this.rec = rec;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;

    // guarded by lock
    private Entry[] buckets = new Entry[16];
    private int size = 0;
    private final int[] counts = new int[4];

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) throw new IllegalArgumentException("installer is null");
        this.installer = installer;
    }

    // ---- helpers (call with lock held where noted) ----

    private static int indexFor(String key, int length) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        return h & (length - 1);
    }

    private Record find(String user) {
        Entry e = buckets[indexFor(user, buckets.length)];
        while (e != null) {
            if (e.key.equals(user)) return e.rec;
            e = e.next;
        }
        return null;
    }

    private void put(Record r) {
        if (size >= buckets.length * 2) {
            Entry[] nb = new Entry[buckets.length * 2];
            for (int i = 0; i < buckets.length; i++) {
                Entry e = buckets[i];
                while (e != null) {
                    Entry next = e.next;
                    int idx = indexFor(e.key, nb.length);
                    e.next = nb[idx];
                    nb[idx] = e;
                    e = next;
                }
            }
            buckets = nb;
        }
        int idx = indexFor(r.user, buckets.length);
        buckets[idx] = new Entry(r.user, r, buckets[idx]);
        size++;
    }

    private void setState(Record r, State s) {
        counts[r.state.ordinal()]--;
        r.state = s;
        counts[s.ordinal()]++;
    }

    private static void checkUser(String user) {
        if (user == null || user.isEmpty()) throw new IllegalArgumentException("invalid user");
    }

    private static String[] copyConfig(String[] configuration) {
        if (configuration == null) throw new IllegalArgumentException("configuration is null");
        String[] c = new String[configuration.length];
        for (int i = 0; i < c.length; i++) {
            if (configuration[i] == null) throw new IllegalArgumentException("null configuration entry");
            c[i] = configuration[i];
        }
        return c;
    }

    private static boolean validManifest(String[] m) {
        if (m == null) return false;
        for (int i = 0; i < m.length; i++) {
            if (m[i] == null) return false;
        }
        return true;
    }

    // ---- public API ----

    public void register(String user, String[] configuration) {
        checkUser(user);
        String[] copy = copyConfig(configuration);
        synchronized (lock) {
            if (find(user) != null) throw new IllegalArgumentException("duplicate user: " + user);
            Record r = new Record(user, copy);
            put(r);
            counts[State.PENDING.ordinal()]++;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        checkUser(user);
        Record rec;
        Attempt attempt;
        String[] cfg;
        synchronized (lock) {
            rec = find(user);
            if (rec == null) throw new IllegalArgumentException("unknown user: " + user);
            switch (rec.state) {
                case ACTIVE:
                    return rec.manifest.clone();
                case FAILED:
                    throw rec.failure;
                case INSTALLING: {
                    Attempt joined = rec.attempt;
                    while (joined.status == RUNNING) {
                        lock.wait();
                    }
                    return result(joined);
                }
                default:
                    attempt = new Attempt(rec.generation);
                    rec.attempt = attempt;
                    rec.manifest = null;
                    rec.failure = null;
                    setState(rec, State.INSTALLING);
                    cfg = rec.config.clone();
            }
        }
        return runAttempt(rec, attempt, cfg);
    }

    public String[] reinstall(String user, String[] configuration) {
        checkUser(user);
        String[] copy = copyConfig(configuration);
        Record rec;
        Attempt attempt;
        String[] cfg;
        synchronized (lock) {
            rec = find(user);
            if (rec == null) throw new IllegalArgumentException("unknown user: " + user);
            if (rec.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }
            Attempt old = rec.attempt;
            if (rec.state == State.INSTALLING && old != null && old.status == RUNNING) {
                old.status = SUPERSEDED;
            }
            rec.generation++;
            rec.config = copy;
            rec.manifest = null;
            rec.failure = null;
            attempt = new Attempt(rec.generation);
            rec.attempt = attempt;
            setState(rec, State.INSTALLING);
            cfg = copy.clone();
            lock.notifyAll();
        }
        return runAttempt(rec, attempt, cfg);
    }

    public Snapshot verify(String user) {
        checkUser(user);
        synchronized (lock) {
            Record rec = find(user);
            if (rec == null) throw new IllegalArgumentException("unknown user: " + user);
            return new Snapshot(rec.state, rec.generation,
                    rec.manifest == null ? null : rec.manifest.clone());
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            return new Metrics(counts[State.PENDING.ordinal()],
                    counts[State.INSTALLING.ordinal()],
                    counts[State.ACTIVE.ordinal()],
                    counts[State.FAILED.ordinal()],
                    size);
        }
    }

    // ---- internals ----

    // call with lock held, attempt not RUNNING
    private static String[] result(Attempt a) {
        if (a.status == SUCCESS) return a.manifest.clone();
        if (a.status == FAILURE) throw a.failure;
        throw new IllegalStateException("installation attempt superseded by newer generation");
    }

    private String[] runAttempt(Record rec, Attempt attempt, String[] cfg) {
        String[] out = null;
        Throwable thrown = null;
        try {
            out = installer.install(rec.user, cfg);
        } catch (Throwable t) {
            thrown = t;
        }
        RuntimeException failure = null;
        String[] manifestCopy = null;
        if (thrown != null) {
            failure = (thrown instanceof RuntimeException)
                    ? (RuntimeException) thrown
                    : new IllegalStateException("installer failed", thrown);
        } else if (!validManifest(out)) {
            failure = new IllegalStateException("installer returned invalid manifest");
        } else {
            manifestCopy = out.clone();
        }
        synchronized (lock) {
            if (attempt.status == SUPERSEDED || rec.attempt != attempt) {
                if (attempt.status == RUNNING) attempt.status = SUPERSEDED;
                lock.notifyAll();
                if (thrown instanceof Error) throw (Error) thrown;
                throw new IllegalStateException("installation attempt superseded by newer generation");
            }
            if (failure != null) {
                attempt.status = FAILURE;
                attempt.failure = failure;
                rec.failure = failure;
                rec.manifest = null;
                setState(rec, State.FAILED);
            } else {
                attempt.status = SUCCESS;
                attempt.manifest = manifestCopy;
                rec.manifest = manifestCopy.clone();
                rec.failure = null;
                setState(rec, State.ACTIVE);
            }
            lock.notifyAll();
        }
        if (thrown instanceof Error) throw (Error) thrown;
        if (failure != null) throw failure;
        return manifestCopy.clone();
    }
}
