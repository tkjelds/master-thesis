@UserSpaceProvisioner.ThreadSafe
public class UserSpaceProvisioner {
    @interface ThreadSafe {}


    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class Snapshot {
        private final String user;
        private final State state;
        private final long generation;
        private final String[] manifest;

        private Snapshot(String user, State state, long generation, String[] manifest) {
            this.user = user;
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public String getUser() {
            return user;
        }

        public State getState() {
            return state;
        }

        public long getGeneration() {
            return generation;
        }

        public String[] getManifest() {
            return copy(manifest);
        }
    }

    public static final class Metrics {
        private final int totalUsers;
        private final int pendingCount;
        private final int installingCount;
        private final int activeCount;
        private final int failedCount;

        private Metrics(int totalUsers, int pendingCount, int installingCount,
                        int activeCount, int failedCount) {
            this.totalUsers = totalUsers;
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
        }

        public int getTotalUsers() {
            return totalUsers;
        }

        public int getPendingCount() {
            return pendingCount;
        }

        public int getInstallingCount() {
            return installingCount;
        }

        public int getActiveCount() {
            return activeCount;
        }

        public int getFailedCount() {
            return failedCount;
        }
    }

    private static final class Attempt {
        final long generation;
        boolean done;
        boolean superseded;
        String[] result;
        RuntimeException failure;

        Attempt(long generation) {
            this.generation = generation;
        }
    }

    private static final class UserRecord {
        final String name;
        String[] configuration;
        State state = State.PENDING;
        long generation;
        String[] manifest;
        RuntimeException failure;
        Attempt attempt;

        UserRecord(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object monitor = new Object();
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
        validateConfig(configuration);
        synchronized (monitor) {
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
            users[userCount++] = new UserRecord(user, copy(configuration));
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        UserRecord rec;
        Attempt attempt;
        String[] config;
        synchronized (monitor) {
            rec = requireLocked(user);
            switch (rec.state) {
                case ACTIVE:
                    return copy(rec.manifest);
                case FAILED:
                    throw rec.failure;
                case INSTALLING:
                    return joinLocked(rec.attempt);
                default:
                    break; // PENDING: claim below
            }
            attempt = claimLocked(rec);
            config = copy(rec.configuration);
        }
        return runInstall(rec, user, attempt, config);
    }

    public String[] reinstall(String user, String[] configuration) throws InterruptedException {
        validateUser(user);
        validateConfig(configuration);
        UserRecord rec;
        Attempt attempt;
        String[] config;
        synchronized (monitor) {
            rec = requireLocked(user);
            if (rec.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }
            if (rec.state == State.INSTALLING && rec.attempt != null) {
                Attempt old = rec.attempt;
                old.superseded = true;
                old.done = true;
                monitor.notifyAll();
            }
            rec.configuration = copy(configuration);
            rec.generation++;
            rec.manifest = null;
            rec.failure = null;
            attempt = claimLocked(rec);
            config = copy(rec.configuration);
        }
        return runInstall(rec, user, attempt, config);
    }

    public Snapshot verify(String user) {
        validateUser(user);
        synchronized (monitor) {
            UserRecord rec = requireLocked(user);
            String[] manifest = rec.state == State.ACTIVE ? copy(rec.manifest) : null;
            return new Snapshot(rec.name, rec.state, rec.generation, manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
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
                }
            }
            return new Metrics(userCount, pending, installing, active, failed);
        }
    }

    // Must be called while holding monitor.
    private Attempt claimLocked(UserRecord rec) {
        rec.state = State.INSTALLING;
        Attempt attempt = new Attempt(rec.generation);
        rec.attempt = attempt;
        return attempt;
    }

    // Must be called while holding monitor.
    private String[] joinLocked(Attempt attempt) throws InterruptedException {
        while (!attempt.done) {
            monitor.wait();
        }
        if (attempt.superseded) {
            throw new IllegalStateException("installation superseded by reinstall");
        }
        if (attempt.failure != null) {
            throw attempt.failure;
        }
        return copy(attempt.result);
    }

    // Invokes the installer outside all monitors, then publishes the outcome
    // only if this attempt is still current.
    private String[] runInstall(UserRecord rec, String user, Attempt attempt, String[] config) {
        String[] result = null;
        RuntimeException failure = null;
        try {
            result = installer.install(user, config);
            if (result == null || hasNullEntry(result)) {
                failure = new IllegalStateException("invalid manifest for user: " + user);
            }
        } catch (RuntimeException e) {
            failure = e;
        } catch (Error e) {
            failure = new IllegalStateException("installer error for user: " + user, e);
        }

        synchronized (monitor) {
            if (attempt.superseded || rec.generation != attempt.generation) {
                throw new IllegalStateException("installation superseded by reinstall");
            }
            if (failure != null) {
                rec.state = State.FAILED;
                rec.failure = failure;
                rec.manifest = null;
                attempt.failure = failure;
                attempt.done = true;
                monitor.notifyAll();
                throw failure;
            }
            rec.state = State.ACTIVE;
            rec.manifest = copy(result);
            attempt.result = rec.manifest;
            attempt.done = true;
            monitor.notifyAll();
            return copy(rec.manifest);
        }
    }

    // Must be called while holding monitor.
    private UserRecord findLocked(String user) {
        for (int i = 0; i < userCount; i++) {
            if (users[i].name.equals(user)) {
                return users[i];
            }
        }
        return null;
    }

    // Must be called while holding monitor.
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

    private static void validateConfig(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (hasNullEntry(configuration)) {
            throw new IllegalArgumentException("configuration entries must not be null");
        }
    }

    private static boolean hasNullEntry(String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                return true;
            }
        }
        return false;
    }

    private static String[] copy(String[] source) {
        if (source == null) {
            return null;
        }
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
