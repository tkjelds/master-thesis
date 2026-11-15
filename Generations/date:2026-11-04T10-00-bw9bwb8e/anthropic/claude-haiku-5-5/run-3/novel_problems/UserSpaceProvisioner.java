import java.lang.Thread;

@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    public enum State { PENDING, INSTALLING, ACTIVE, FAILED }

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public static final class Status {
        private final String user;
        private final State state;
        private final long generation;
        private final String[] manifest;

        private Status(String user, State state, long generation, String[] manifest) {
            this.user = user;
            this.state = state;
            this.generation = generation;
            this.manifest = (manifest == null) ? null : manifest.clone();
        }

        public String getUser() { return user; }
        public State getState() { return state; }
        public long getGeneration() { return generation; }
        public String[] getManifest() { return (manifest == null) ? null : manifest.clone(); }
    }

    public static final class Metrics {
        private final int pending;
        private final int installing;
        private final int active;
        private final int failed;
        private final int total;

        private Metrics(int pending, int installing, int active, int failed, int total) {
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

    private static final class Attempt {
        final long generation;
        final String[] configuration; // immutable private copy
        boolean done;
        boolean superseded;
        String[] manifest;            // immutable once published; always cloned on return
        RuntimeException failure;

        Attempt(long generation, String[] configuration) {
            this.generation = generation;
            this.configuration = configuration;
        }
    }

    private static final class UserRecord {
        final String name;
        String[] configuration;       // immutable copy
        State state = State.PENDING;
        long generation;
        String[] manifest;            // non-null only when ACTIVE
        Attempt current;              // non-null only when INSTALLING
        RuntimeException failure;     // non-null only when FAILED

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object lock = new Object();
    private final Installer installer;
    private UserRecord[] users = new UserRecord[8];
    private int userCount;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copy = copyConfiguration(configuration);
        synchronized (lock) {
            if (findLocked(user) != null) {
                throw new IllegalArgumentException("duplicate user: " + user);
            }
            if (userCount == users.length) {
                UserRecord[] grown = new UserRecord[users.length * 2];
                for (int i = 0; i < userCount; i++) {
                    grown[i] = users[i];
                }
                users = grown;
            }
            users[userCount++] = new UserRecord(user, copy);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        UserRecord rec;
        Attempt attempt;
        synchronized (lock) {
            rec = requireLocked(user);
            while (true) {
                if (rec.state == State.ACTIVE) {
                    return rec.manifest.clone();
                }
                if (rec.state == State.FAILED) {
                    throw rec.failure;
                }
                if (rec.state == State.PENDING) {
                    attempt = startAttemptLocked(rec);
                    break;
                }
                // INSTALLING: join the in-flight attempt.
                return awaitAttemptLocked(rec.current);
            }
        }
        return runAttempt(rec, attempt);
    }

    public String[] reinstall(String user, String[] configuration) throws InterruptedException {
        validateUser(user);
        String[] copy = copyConfiguration(configuration);
        UserRecord rec;
        Attempt attempt;
        synchronized (lock) {
            rec = requireLocked(user);
            if (rec.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }
            rec.configuration = copy;
            rec.generation++;
            rec.manifest = null;
            rec.failure = null;
            if (rec.current != null) {
                rec.current.superseded = true;
                rec.current.done = true;
            }
            attempt = new Attempt(rec.generation, copy);
            rec.current = attempt;
            rec.state = State.INSTALLING;
            lock.notifyAll();
        }
        return runAttempt(rec, attempt);
    }

    public Status verify(String user) {
        validateUser(user);
        synchronized (lock) {
            UserRecord rec = requireLocked(user);
            return new Status(rec.name, rec.state, rec.generation, rec.manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (lock) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;
            for (int i = 0; i < userCount; i++) {
                switch (users[i].state) {
                    case PENDING: pending++; break;
                    case INSTALLING: installing++; break;
                    case ACTIVE: active++; break;
                    case FAILED: failed++; break;
                    default: break;
                }
            }
            return new Metrics(pending, installing, active, failed, userCount);
        }
    }

    // ---- internal helpers ----

    // Must hold lock.
    private Attempt startAttemptLocked(UserRecord rec) {
        Attempt a = new Attempt(rec.generation, rec.configuration);
        rec.current = a;
        rec.state = State.INSTALLING;
        return a;
    }

    // Must hold lock. Waits until the attempt completes, then returns its result.
    private String[] awaitAttemptLocked(Attempt a) throws InterruptedException {
        while (!a.done) {
            lock.wait();
        }
        if (a.superseded) {
            throw new IllegalStateException("installation attempt superseded by reinstall");
        }
        if (a.failure != null) {
            throw a.failure;
        }
        return a.manifest.clone();
    }

    // Runs the installer outside all shared monitors, then publishes the result.
    private String[] runAttempt(UserRecord rec, Attempt a) {
        String[] manifest = null;
        RuntimeException failure = null;
        try {
            String[] result = installer.install(rec.name, a.configuration.clone());
            manifest = validateManifest(result);
        } catch (RuntimeException e) {
            failure = e;
        } catch (Error e) {
            failure = new IllegalStateException("installer error", e);
        }

        boolean superseded;
        synchronized (lock) {
            a.done = true;
            if (rec.current == a) {
                rec.current = null;
                if (failure != null) {
                    rec.state = State.FAILED;
                    rec.failure = failure;
                    rec.manifest = null;
                } else {
                    rec.state = State.ACTIVE;
                    rec.manifest = manifest;
                    rec.failure = null;
                }
                superseded = false;
            } else {
                a.superseded = true;
                superseded = true;
            }
            if (!superseded) {
                a.manifest = manifest;
                a.failure = failure;
            }
            lock.notifyAll();
        }

        if (superseded) {
            throw new IllegalStateException("installation attempt superseded by reinstall");
        }
        if (failure != null) {
            throw failure;
        }
        return manifest.clone();
    }

    // Must hold lock.
    private UserRecord findLocked(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(user)) {
                return users[i];
            }
        }
        return null;
    }

    // Must hold lock.
    private UserRecord requireLocked(String user) {
        UserRecord rec = findLocked(user);
        if (rec == null) {
            throw new IllegalArgumentException("unknown user: " + user);
        }
        return rec;
    }

    private static void validateUser(String user) {
        if (user == null || user.isEmpty()) {
            throw new IllegalArgumentException("username must be non-null and non-empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException("configuration entries must not be null");
            }
        }
        return configuration.clone();
    }

    private static String[] validateManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("installer returned null manifest");
        }
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException("installer returned null manifest entry");
            }
        }
        return manifest.clone();
    }
}
