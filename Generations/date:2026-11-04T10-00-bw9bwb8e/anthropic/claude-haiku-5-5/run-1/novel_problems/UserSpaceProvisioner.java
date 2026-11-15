import java.lang.reflect.Array;

@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}


    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class Status {
        private final State state;
        private final long generation;
        private final String[] manifest;

        Status(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public State getState() {
            return state;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return copyOf(manifest);
        }
    }

    public static final class Metrics {
        private final int pending;
        private final int installing;
        private final int active;
        private final int failed;
        private final int totalUsers;

        Metrics(int pending, int installing, int active, int failed, int totalUsers) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = totalUsers;
        }

        public int getPending() {
            return pending;
        }

        public int getInstalling() {
            return installing;
        }

        public int getActive() {
            return active;
        }

        public int getFailed() {
            return failed;
        }

        public int getTotalUsers() {
            return totalUsers;
        }
    }

    private static final class Attempt {
        final long generation;
        boolean done;
        boolean superseded;
        String[] manifest;
        RuntimeException failure;

        Attempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class UserRecord {
        final String name;
        String[] configuration;
        State state;
        long generation;
        String[] manifest;
        RuntimeException failure;
        Attempt attempt;

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.state = State.PENDING;
            this.generation = 1L;
        }
    }

    // All mutable state is guarded by 'lock'.
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
        checkUser(user);
        checkConfiguration(configuration);
        String[] copy = copyOf(configuration);
        synchronized (lock) {
            if (findLocked(user) != null) {
                throw new IllegalArgumentException("duplicate user: " + user);
            }
            addLocked(new UserRecord(user, copy));
        }
    }

    public String[] activate(String user) throws InterruptedException {
        checkUser(user);
        UserRecord r;
        Attempt attempt;
        String[] config;
        synchronized (lock) {
            r = requireLocked(user);
            if (r.state == State.ACTIVE) {
                return copyOf(r.manifest);
            }
            if (r.state == State.FAILED) {
                throw r.failure;
            }
            if (r.state == State.INSTALLING) {
                return awaitLocked(r.attempt);
            }
            // PENDING: claim this generation.
            attempt = new Attempt(r.generation);
            r.attempt = attempt;
            r.state = State.INSTALLING;
            config = copyOf(r.configuration);
        }
        return runInstall(r, attempt, config);
    }

    public String[] reinstall(String user, String[] configuration) {
        checkUser(user);
        checkConfiguration(configuration);
        String[] copy = copyOf(configuration);
        UserRecord r;
        Attempt attempt;
        String[] config;
        synchronized (lock) {
            r = requireLocked(user);
            if (r.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }
            if (r.state == State.INSTALLING && r.attempt != null) {
                Attempt old = r.attempt;
                old.superseded = true;
                old.done = true;
            }
            r.generation++;
            r.configuration = copy;
            r.manifest = null;
            r.failure = null;
            r.state = State.INSTALLING;
            attempt = new Attempt(r.generation);
            r.attempt = attempt;
            config = copyOf(copy);
            lock.notifyAll();
        }
        return runInstall(r, attempt, config);
    }

    public String[] verify(String user) {
        checkUser(user);
        synchronized (lock) {
            UserRecord r = requireLocked(user);
            String[] manifest = (r.state == State.ACTIVE) ? copyOf(r.manifest) : null;
            return null == manifest && r.state == State.ACTIVE
                    ? new String[0]
                    : (manifest == null ? null : manifest);
        }
    }

    public Status snapshotStatus(String user) {
        checkUser(user);
        synchronized (lock) {
            UserRecord r = requireLocked(user);
            String[] manifest = (r.state == State.ACTIVE) ? copyOf(r.manifest) : null;
            return new Status(r.state, r.generation, manifest);
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
                    case PENDING:
                        pending++;
                        break;
                    case INSTALLING:
                        installing++;
                        break;
                    case ACTIVE:
                        active++;
                        break;
                    case FAILED:
                        failed++;
                        break;
                    default:
                        break;
                }
            }
            return new Metrics(pending, installing, active, failed, userCount);
        }
    }

    // Runs the installer outside all monitors, then publishes the outcome.
    private String[] runInstall(UserRecord r, Attempt attempt, String[] config) {
        String[] result = null;
        RuntimeException failure = null;
        try {
            String[] raw = installer.install(r.name, config);
            if (!isValidManifest(raw)) {
                failure = new IllegalStateException("invalid manifest for user: " + r.name);
            } else {
                result = copyOf(raw);
            }
        } catch (RuntimeException e) {
            failure = e;
        } catch (Error e) {
            failure = new IllegalStateException("installer error for user: " + r.name, e);
        }
        return publish(r, attempt, result, failure);
    }

    private String[] publish(UserRecord r, Attempt attempt, String[] manifest, RuntimeException failure) {
        synchronized (lock) {
            if (attempt.superseded) {
                throw new IllegalStateException("installation superseded for user: " + r.name);
            }
            attempt.done = true;
            if (failure == null) {
                r.state = State.ACTIVE;
                r.manifest = manifest;
                r.failure = null;
                attempt.manifest = manifest;
            } else {
                r.state = State.FAILED;
                r.manifest = null;
                r.failure = failure;
                attempt.failure = failure;
            }
            r.attempt = null;
            lock.notifyAll();
        }
        if (failure != null) {
            throw failure;
        }
        return copyOf(manifest);
    }

    // Must be called while holding lock.
    private String[] awaitLocked(Attempt a) throws InterruptedException {
        while (!a.done) {
            lock.wait();
        }
        if (a.superseded) {
            throw new IllegalStateException("installation superseded");
        }
        if (a.failure != null) {
            throw a.failure;
        }
        return copyOf(a.manifest);
    }

    // Must be called while holding lock.
    private UserRecord findLocked(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(user)) {
                return users[i];
            }
        }
        return null;
    }

    // Must be called while holding lock.
    private UserRecord requireLocked(String user) {
        UserRecord r = findLocked(user);
        if (r == null) {
            throw new IllegalArgumentException("unknown user: " + user);
        }
        return r;
    }

    // Must be called while holding lock.
    private void addLocked(UserRecord r) {
        if (userCount == users.length) {
            UserRecord[] grown = new UserRecord[users.length * 2];
            for (int i = 0; i < userCount; i++) {
                grown[i] = users[i];
            }
            users = grown;
        }
        users[userCount++] = r;
    }

    private static void checkUser(String user) {
        if (user == null || user.isEmpty()) {
            throw new IllegalArgumentException("username must be non-null and non-empty");
        }
    }

    private static void checkConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException("configuration entries must not be null");
            }
        }
    }

    private static boolean isValidManifest(String[] manifest) {
        if (manifest == null) {
            return false;
        }
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                return false;
            }
        }
        return true;
    }

    private static String[] copyOf(String[] source) {
        if (source == null) {
            return null;
        }
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
