import java.lang.*;

@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State { PENDING, INSTALLING, ACTIVE, FAILED }

    public static final class Snapshot {
        private final State status;
        private final long generation;
        private final String[] manifest;

        Snapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = manifest == null ? null : manifest.clone();
        }

        public State getStatus() { return status; }
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

    private static final class Record {
        final String name;
        Record next;
        String[] config;
        State state = State.PENDING;
        long generation = 1;
        String[] manifest;
        RuntimeException failure;

        Record(String name, String[] config) {
            this.name = name;
            this.config = config;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private Record[] table = new Record[16];
    private int size = 0;
    private final int[] counts = new int[4];

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) throw new IllegalArgumentException("installer is null");
        this.installer = installer;
    }

    // ---- helpers (call with lock held where noted) ----

    private static String[] copyConfig(String[] configuration) {
        if (configuration == null) throw new IllegalArgumentException("configuration is null");
        String[] c = new String[configuration.length];
        for (int i = 0; i < c.length; i++) {
            if (configuration[i] == null) throw new IllegalArgumentException("null configuration entry");
            c[i] = configuration[i];
        }
        return c;
    }

    private static void checkName(String user) {
        if (user == null || user.isEmpty()) throw new IllegalArgumentException("invalid username");
    }

    private Record find(String user) {
        int idx = (user.hashCode() & 0x7fffffff) % table.length;
        for (Record r = table[idx]; r != null; r = r.next) {
            if (r.name.equals(user)) return r;
        }
        return null;
    }

    private Record require(String user) {
        checkName(user);
        Record r = find(user);
        if (r == null) throw new IllegalArgumentException("unknown user: " + user);
        return r;
    }

    private void insert(Record rec) {
        if (size + 1 > table.length * 3 / 4) {
            Record[] nt = new Record[table.length * 2];
            for (int i = 0; i < table.length; i++) {
                Record r = table[i];
                while (r != null) {
                    Record nx = r.next;
                    int idx = (r.name.hashCode() & 0x7fffffff) % nt.length;
                    r.next = nt[idx];
                    nt[idx] = r;
                    r = nx;
                }
            }
            table = nt;
        }
        int idx = (rec.name.hashCode() & 0x7fffffff) % table.length;
        rec.next = table[idx];
        table[idx] = rec;
        size++;
    }

    private void setState(Record r, State s) {
        counts[r.state.ordinal()]--;
        r.state = s;
        counts[s.ordinal()]++;
    }

    // ---- public API ----

    public void register(String user, String[] configuration) {
        checkName(user);
        String[] copy = copyConfig(configuration);
        synchronized (lock) {
            if (find(user) != null) throw new IllegalArgumentException("duplicate user: " + user);
            Record r = new Record(user, copy);
            insert(r);
            counts[State.PENDING.ordinal()]++;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        checkName(user);
        long gen;
        String[] cfg;
        synchronized (lock) {
            Record rec = require(user);
            switch (rec.state) {
                case ACTIVE:
                    return rec.manifest.clone();
                case FAILED:
                    throw rec.failure;
                case INSTALLING: {
                    long myGen = rec.generation;
                    while (rec.generation == myGen && rec.state == State.INSTALLING) {
                        lock.wait();
                    }
                    if (rec.generation != myGen) {
                        throw new IllegalStateException(
                                "installation superseded for user: " + user);
                    }
                    if (rec.state == State.ACTIVE) return rec.manifest.clone();
                    if (rec.state == State.FAILED) throw rec.failure;
                    throw new IllegalStateException("unexpected state for user: " + user);
                }
                default: // PENDING
                    setState(rec, State.INSTALLING);
                    gen = rec.generation;
                    cfg = rec.config.clone();
                    break;
            }
        }
        return runInstall(user, gen, cfg);
    }

    public String[] reinstall(String user, String[] configuration) {
        checkName(user);
        String[] copy = copyConfig(configuration);
        long gen;
        String[] cfg;
        synchronized (lock) {
            Record rec = require(user);
            if (rec.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }
            rec.generation++;
            rec.config = copy;
            rec.manifest = null;
            rec.failure = null;
            setState(rec, State.INSTALLING);
            gen = rec.generation;
            cfg = copy.clone();
            lock.notifyAll(); // wake joiners of superseded attempt
        }
        return runInstall(user, gen, cfg);
    }

    public Snapshot verify(String user) {
        synchronized (lock) {
            Record rec = require(user);
            return new Snapshot(rec.state, rec.generation, rec.manifest);
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

    // Runs the callback outside the monitor, then publishes if still current.
    private String[] runInstall(String user, long gen, String[] cfg) {
        String[] manifest = null;
        RuntimeException failure = null;
        Error fatal = null;
        try {
            String[] result = installer.install(user, cfg);
            if (result == null) {
                failure = new IllegalStateException("installer returned null manifest");
            } else {
                String[] copy = new String[result.length];
                boolean ok = true;
                for (int i = 0; i < copy.length; i++) {
                    if (result[i] == null) { ok = false; break; }
                    copy[i] = result[i];
                }
                if (ok) manifest = copy;
                else failure = new IllegalStateException("installer returned manifest with null entry");
            }
        } catch (RuntimeException e) {
            failure = e;
        } catch (Error e) {
            fatal = e;
            failure = new IllegalStateException("installer failed with error: " + e);
        }

        synchronized (lock) {
            Record rec = find(user);
            if (rec != null && rec.generation == gen && rec.state == State.INSTALLING) {
                if (failure == null) {
                    rec.manifest = manifest;
                    rec.failure = null;
                    setState(rec, State.ACTIVE);
                } else {
                    rec.manifest = null;
                    rec.failure = failure;
                    setState(rec, State.FAILED);
                }
                lock.notifyAll();
            } else {
                lock.notifyAll();
                if (fatal != null) throw fatal;
                throw new IllegalStateException("installation superseded for user: " + user);
            }
            if (fatal != null) throw fatal;
            if (failure != null) throw failure;
            return manifest.clone();
        }
    }
}
