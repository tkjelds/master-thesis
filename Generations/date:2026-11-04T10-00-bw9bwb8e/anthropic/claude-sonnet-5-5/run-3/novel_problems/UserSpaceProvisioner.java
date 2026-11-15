import java.lang.Thread;

@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State { PENDING, INSTALLING, ACTIVE, FAILED }

    public static final class Status {
        private final State state;
        private final long generation;
        private final String[] manifest;

        Status(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public State getState() { return state; }
        public long getGeneration() { return generation; }
        public String[] getManifest() { return manifest == null ? null : manifest.clone(); }
    }

    public static final class Metrics {
        public final int pending;
        public final int installing;
        public final int active;
        public final int failed;
        public final int total;

        Metrics(int pending, int installing, int active, int failed) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = pending + installing + active + failed;
        }
    }

    private static final class Record {
        final String name;
        String[] config;
        State state = State.PENDING;
        long generation = 1;
        String[] manifest;
        RuntimeException failure;
        Thread installThread;

        Record(String name, String[] config) {
            this.name = name;
            this.config = config;
        }
    }

    private static final class Node {
        final String key;
        final Record rec;
        Node next;

        Node(String key, Record rec, Node next) {
            this.key = key;
            this.rec = rec;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    // all below guarded by lock
    private Node[] table = new Node[16];
    private int size = 0;
    private final int[] counts = new int[4];

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) throw new IllegalArgumentException("installer is null");
        this.installer = installer;
    }

    // ---- helpers (call with lock held) ----

    private Record find(String user) {
        if (user == null) return null;
        int idx = (user.hashCode() & 0x7fffffff) % table.length;
        for (Node n = table[idx]; n != null; n = n.next) {
            if (n.key.equals(user)) return n.rec;
        }
        return null;
    }

    private Record require(String user) {
        Record r = find(user);
        if (r == null) throw new IllegalArgumentException("unknown user: " + user);
        return r;
    }

    private void put(Record r) {
        if (size + 1 > table.length * 3 / 4) {
            Node[] nt = new Node[table.length * 2];
            for (int i = 0; i < table.length; i++) {
                Node n = table[i];
                while (n != null) {
                    Node next = n.next;
                    int idx = (n.key.hashCode() & 0x7fffffff) % nt.length;
                    n.next = nt[idx];
                    nt[idx] = n;
                    n = next;
                }
            }
            table = nt;
        }
        int idx = (r.name.hashCode() & 0x7fffffff) % table.length;
        table[idx] = new Node(r.name, r, table[idx]);
        size++;
    }

    private void transition(Record r, State s) {
        counts[r.state.ordinal()]--;
        r.state = s;
        counts[s.ordinal()]++;
    }

    private static String[] copyConfig(String[] c) {
        if (c == null) throw new IllegalArgumentException("configuration is null");
        String[] copy = c.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) throw new IllegalArgumentException("null configuration entry");
        }
        return copy;
    }

    // ---- public API ----

    public void register(String user, String[] configuration) {
        if (user == null || user.isEmpty()) throw new IllegalArgumentException("invalid user name");
        String[] copy = copyConfig(configuration);
        synchronized (lock) {
            if (find(user) != null) throw new IllegalArgumentException("duplicate user: " + user);
            Record r = new Record(user, copy);
            put(r);
            counts[State.PENDING.ordinal()]++;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        Record rec;
        long gen;
        String[] cfg;
        synchronized (lock) {
            rec = require(user);
            switch (rec.state) {
                case ACTIVE:
                    return rec.manifest.clone();
                case FAILED:
                    throw rec.failure;
                case INSTALLING:
                    if (rec.installThread == Thread.currentThread()) {
                        throw new IllegalStateException("recursive activation from installer callback");
                    }
                    return await(rec, rec.generation);
                default:
                    transition(rec, State.INSTALLING);
                    rec.installThread = Thread.currentThread();
                    gen = rec.generation;
                    cfg = rec.config.clone();
                    break;
            }
        }
        return runAttempt(rec, gen, cfg);
    }

    public String[] reinstall(String user, String[] configuration) {
        String[] copy = copyConfig(configuration);
        Record rec;
        long gen;
        synchronized (lock) {
            rec = require(user);
            if (rec.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow");
            }
            if (rec.state == State.INSTALLING && rec.installThread == Thread.currentThread()) {
                throw new IllegalStateException("recursive reinstall from installer callback");
            }
            rec.generation++;
            gen = rec.generation;
            rec.config = copy;
            rec.manifest = null;
            rec.failure = null;
            transition(rec, State.INSTALLING);
            rec.installThread = Thread.currentThread();
            lock.notifyAll(); // wake superseded waiters
        }
        return runAttempt(rec, gen, copy.clone());
    }

    public Status verify(String user) {
        synchronized (lock) {
            Record rec = require(user);
            return new Status(rec.state, rec.generation,
                    rec.manifest == null ? null : rec.manifest.clone());
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            return new Metrics(counts[0], counts[1], counts[2], counts[3]);
        }
    }

    // ---- internals ----

    // Must be called with lock held.
    private String[] await(Record rec, long gen) throws InterruptedException {
        while (true) {
            if (rec.generation != gen) {
                throw new IllegalStateException("installation attempt superseded for user " + rec.name);
            }
            if (rec.state == State.ACTIVE) return rec.manifest.clone();
            if (rec.state == State.FAILED) throw rec.failure;
            lock.wait();
        }
    }

    private String[] runAttempt(Record rec, long gen, String[] cfg) {
        String[] out = null;
        RuntimeException failure = null;
        try {
            out = installer.install(rec.name, cfg);
        } catch (RuntimeException e) {
            failure = e;
        } catch (Error e) {
            publish(rec, gen, null, new IllegalStateException("installer failed with error", e));
            throw e;
        }
        String[] manifest = null;
        if (failure == null) {
            if (out == null) {
                failure = new IllegalStateException("installer returned null manifest");
            } else {
                manifest = out.clone();
                for (int i = 0; i < manifest.length; i++) {
                    if (manifest[i] == null) {
                        failure = new IllegalStateException("installer returned manifest with null entry");
                        manifest = null;
                        break;
                    }
                }
            }
        }
        boolean published = publish(rec, gen, manifest, failure);
        if (!published) {
            throw new IllegalStateException("installation attempt superseded for user " + rec.name);
        }
        if (failure != null) throw failure;
        return manifest.clone();
    }

    private boolean publish(Record rec, long gen, String[] manifest, RuntimeException failure) {
        synchronized (lock) {
            if (rec.generation != gen || rec.state != State.INSTALLING) {
                return false;
            }
            rec.installThread = null;
            if (failure == null) {
                rec.manifest = manifest;
                rec.failure = null;
                transition(rec, State.ACTIVE);
            } else {
                rec.manifest = null;
                rec.failure = failure;
                transition(rec, State.FAILED);
            }
            lock.notifyAll();
            return true;
        }
    }
}
