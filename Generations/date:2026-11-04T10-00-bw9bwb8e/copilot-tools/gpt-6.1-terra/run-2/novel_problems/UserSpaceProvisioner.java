@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class StatusSnapshot {
        public final State state;
        public final long generation;
        private final String[] manifest;

        private StatusSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public String[] getManifest() {
            return manifest == null ? null : manifest.clone();
        }
    }

    public static final class MetricsSnapshot {
        public final int pending;
        public final int installing;
        public final int active;
        public final int failed;
        public final int total;

        private MetricsSnapshot(int pending, int installing, int active,
                                int failed, int total) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = total;
        }
    }

    private static final class Attempt {
        boolean done;
        boolean superseded;
        Throwable failure;
        String[] manifest;
    }

    private static final class User {
        final String name;
        String[] configuration;
        long generation = 1;
        State state = State.PENDING;
        Attempt attempt;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private User[] users = new User[8];
    private int size;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copy = copyConfiguration(configuration);
        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("user already registered: " + user);
            }
            if (size == users.length) {
                int newLength = users.length <= 1073741823
                        ? users.length * 2 : 2147483647;
                if (newLength == users.length) {
                    throw new IllegalStateException("user capacity exhausted");
                }
                User[] expanded = new User[newLength];
                for (int i = 0; i < size; i++) {
                    expanded[i] = users[i];
                }
                users = expanded;
            }
            users[size++] = new User(user, copy);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        User entry;
        Attempt attempt;
        String[] configuration = null;
        boolean install = false;
        synchronized (monitor) {
            entry = requireUser(user);
            if (entry.state == State.PENDING) {
                attempt = new Attempt();
                entry.attempt = attempt;
                entry.state = State.INSTALLING;
                configuration = entry.configuration;
                install = true;
            } else {
                attempt = entry.attempt;
            }
        }
        if (install) {
            return runInstallation(entry, attempt, configuration);
        }
        return awaitAttempt(attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copy = copyConfiguration(configuration);
        User entry;
        Attempt attempt = new Attempt();
        synchronized (monitor) {
            entry = requireUser(user);
            if (entry.generation == 9223372036854775807L) {
                throw new IllegalStateException("generation overflow for user: " + user);
            }
            if (entry.state == State.INSTALLING) {
                entry.attempt.superseded = true;
                entry.attempt.done = true;
            }
            entry.configuration = copy;
            entry.generation++;
            entry.attempt = attempt;
            entry.state = State.INSTALLING;
            monitor.notifyAll();
        }
        return runInstallation(entry, attempt, copy);
    }

    public StatusSnapshot verify(String user) {
        validateUser(user);
        synchronized (monitor) {
            User entry = requireUser(user);
            String[] manifest = entry.state == State.ACTIVE
                    ? entry.attempt.manifest.clone() : null;
            return new StatusSnapshot(entry.state, entry.generation, manifest);
        }
    }

    public MetricsSnapshot snapshotMetrics() {
        synchronized (monitor) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;
            for (int i = 0; i < size; i++) {
                switch (users[i].state) {
                    case PENDING: pending++; break;
                    case INSTALLING: installing++; break;
                    case ACTIVE: active++; break;
                    case FAILED: failed++; break;
                }
            }
            return new MetricsSnapshot(pending, installing, active, failed, size);
        }
    }

    private String[] runInstallation(User entry, Attempt attempt, String[] configuration) {
        String[] manifest = null;
        Throwable failure = null;
        try {
            String[] result = installer.install(entry.name, configuration.clone());
            if (result == null) {
                throw new IllegalStateException("installer returned a null manifest");
            }
            manifest = result.clone();
            for (int i = 0; i < manifest.length; i++) {
                if (manifest[i] == null) {
                    throw new IllegalStateException("installer returned a null manifest entry");
                }
            }
        } catch (RuntimeException | Error problem) {
            failure = problem;
        }

        synchronized (monitor) {
            if (!attempt.superseded) {
                attempt.failure = failure;
                attempt.manifest = failure == null ? manifest : null;
                attempt.done = true;
                entry.state = failure == null ? State.ACTIVE : State.FAILED;
            }
            monitor.notifyAll();
        }
        return completedResult(attempt);
    }

    private String[] awaitAttempt(Attempt attempt) throws InterruptedException {
        synchronized (monitor) {
            while (!attempt.done) {
                monitor.wait();
            }
        }
        return completedResult(attempt);
    }

    private String[] completedResult(Attempt attempt) {
        String[] result;
        synchronized (monitor) {
            if (attempt.superseded) {
                throw new IllegalStateException("installation superseded by a newer generation");
            }
            if (attempt.failure instanceof RuntimeException) {
                throw (RuntimeException) attempt.failure;
            }
            if (attempt.failure instanceof Error) {
                throw (Error) attempt.failure;
            }
            result = attempt.manifest;
        }
        return result.clone();
    }

    private User findUser(String name) {
        for (int i = 0; i < size; i++) {
            if (users[i].name.equals(name)) {
                return users[i];
            }
        }
        return null;
    }

    private User requireUser(String name) {
        User entry = findUser(name);
        if (entry == null) {
            throw new IllegalArgumentException("unknown user: " + name);
        }
        return entry;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("user must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        String[] copy = configuration.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new IllegalArgumentException("configuration entry must not be null");
            }
        }
        return copy;
    }
}
