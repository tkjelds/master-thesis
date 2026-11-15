@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class Snapshot {
        public final State state;
        public final long generation;
        private final String[] manifest;

        private Snapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest == null ? null : copy(manifest);
        }

        public String[] getManifest() {
            return manifest == null ? null : copy(manifest);
        }
    }

    public static final class Metrics {
        public final long pending;
        public final long installing;
        public final long active;
        public final long failed;
        public final long totalUsers;

        private Metrics(long pending, long installing, long active, long failed) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.totalUsers = pending + installing + active + failed;
        }
    }

    private static final class User {
        final String name;
        final User next;
        String[] configuration;
        long generation = 1;
        State state = State.PENDING;
        String[] manifest;
        Attempt current;
        Attempt running;

        User(String name, String[] configuration, User next) {
            this.name = name;
            this.configuration = configuration;
            this.next = next;
        }
    }

    private static final class Attempt {
        final long generation;
        final String[] configuration;
        final Thread owner;
        Attempt runningNext;
        boolean completed;
        String[] manifest;
        Throwable failure;

        Attempt(long generation, String[] configuration) {
            this.generation = generation;
            this.configuration = configuration;
            this.owner = Thread.currentThread();
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private User users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("Installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] saved = copyConfiguration(configuration);
        synchronized (monitor) {
            if (find(user) != null) {
                throw new IllegalArgumentException("User already registered: " + user);
            }
            users = new User(user, saved, users);
        }
    }

    /**
     * The claiming caller runs the installer synchronously. Joining callers
     * wait interruptibly; interruption does not cancel the shared attempt.
     */
    public String[] activate(String user) throws InterruptedException {
        User record;
        Attempt attempt;
        boolean claimed = false;
        synchronized (monitor) {
            record = requireUser(user);
            rejectRecursiveInstallation(record);
            if (record.state == State.ACTIVE) {
                return copy(record.manifest);
            }
            if (record.state == State.PENDING) {
                attempt = start(record, record.generation, record.configuration);
                claimed = true;
            } else {
                attempt = record.current;
            }
        }
        if (claimed) {
            runInstallation(record, attempt);
        }
        return await(attempt);
    }

    /**
     * Replaces the configuration and synchronously runs a new generation.
     * Superseded callers fail even if their older installer later succeeds.
     */
    public String[] reinstall(String user, String[] configuration)
            throws InterruptedException {
        validateUser(user);
        String[] saved = copyConfiguration(configuration);
        User record;
        Attempt attempt;
        synchronized (monitor) {
            record = requireUser(user);
            rejectRecursiveInstallation(record);
            if (record.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("Generation overflow: " + user);
            }
            attempt = start(record, record.generation + 1, saved);
        }
        runInstallation(record, attempt);
        return await(attempt);
    }

    public Snapshot verify(String user) {
        synchronized (monitor) {
            User record = requireUser(user);
            return new Snapshot(record.state, record.generation, record.manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;
            for (User user = users; user != null; user = user.next) {
                switch (user.state) {
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
            return new Metrics(pending, installing, active, failed);
        }
    }

    // Called with monitor held; allocate before changing any published state.
    private Attempt start(User user, long generation, String[] configuration) {
        Attempt next = new Attempt(generation, configuration);
        Attempt old = user.current;
        if (old != null && !old.completed) {
            IllegalStateException superseded = new IllegalStateException(
                    "Installation superseded for user " + user.name
                    + ", generation " + old.generation);
            old.failure = superseded;
            old.completed = true;
        }
        next.runningNext = user.running;
        user.running = next;
        user.configuration = configuration;
        user.generation = generation;
        user.current = next;
        user.manifest = null;
        user.state = State.INSTALLING;
        monitor.notifyAll();
        return next;
    }

    private void runInstallation(User user, Attempt attempt) {
        String[] manifest;
        try {
            String[] supplied = installer.install(
                    user.name, copy(attempt.configuration));
            if (supplied == null) {
                throw new IllegalStateException("Installer returned a null manifest");
            }
            manifest = copy(supplied);
            for (String entry : manifest) {
                if (entry == null) {
                    throw new IllegalStateException(
                            "Installer returned a null manifest entry");
                }
            }
        } catch (RuntimeException failure) {
            finish(user, attempt, null, failure);
            return;
        } catch (Error failure) {
            // Errors must also release joiners rather than strand INSTALLING.
            finish(user, attempt, null, failure);
            return;
        }
        finish(user, attempt, manifest, null);
    }

    private void finish(User user, Attempt attempt, String[] manifest,
                        Throwable failure) {
        synchronized (monitor) {
            Attempt previous = null;
            Attempt running = user.running;
            while (running != attempt) {
                previous = running;
                running = running.runningNext;
            }
            if (previous == null) {
                user.running = attempt.runningNext;
            } else {
                previous.runningNext = attempt.runningNext;
            }
            attempt.runningNext = null;

            if (user.current == attempt && user.generation == attempt.generation) {
                attempt.manifest = manifest;
                attempt.failure = failure;
                attempt.completed = true;
                user.manifest = manifest;
                user.state = failure == null ? State.ACTIVE : State.FAILED;
            }
            monitor.notifyAll();
        }
    }

    private String[] await(Attempt attempt) throws InterruptedException {
        synchronized (monitor) {
            while (!attempt.completed) {
                monitor.wait();
            }
            if (attempt.failure instanceof Error) {
                throw (Error) attempt.failure;
            }
            if (attempt.failure != null) {
                throw (RuntimeException) attempt.failure;
            }
            return copy(attempt.manifest);
        }
    }

    // Keep superseded callbacks in this list until they actually return.
    private void rejectRecursiveInstallation(User user) {
        Thread caller = Thread.currentThread();
        for (Attempt attempt = user.running; attempt != null;
                attempt = attempt.runningNext) {
            if (attempt.owner == caller) {
                throw new IllegalStateException(
                        "Installer cannot activate or reinstall its own user: "
                        + user.name);
            }
        }
    }

    private User requireUser(String name) {
        validateUser(name);
        User user = find(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }

    private User find(String name) {
        for (User user = users; user != null; user = user.next) {
            if (user.name.equals(name)) {
                return user;
            }
        }
        return null;
    }

    private static void validateUser(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        String[] result = copy(configuration);
        for (String entry : result) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Configuration entries must not be null");
            }
        }
        return result;
    }

    private static String[] copy(String[] source) {
        String[] result = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i];
        }
        return result;
    }
}
