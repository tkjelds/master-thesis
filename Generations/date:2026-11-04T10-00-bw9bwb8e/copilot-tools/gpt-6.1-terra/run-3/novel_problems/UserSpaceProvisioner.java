@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING, INSTALLING, ACTIVE, FAILED
    }

    public static final class Verification {
        public final State state;
        public final long generation;
        private final String[] manifest;

        private Verification(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest;
        }

        public String[] getManifest() {
            return manifest == null ? null : manifest.clone();
        }
    }

    public static final class Metrics {
        public final long pending;
        public final long installing;
        public final long active;
        public final long failed;
        public final long total;

        private Metrics(long pending, long installing, long active, long failed,
                        long total) {
            this.pending = pending;
            this.installing = installing;
            this.active = active;
            this.failed = failed;
            this.total = total;
        }
    }

    private static final int RUNNING = 0;
    private static final int SUCCEEDED = 1;
    private static final int ATTEMPT_FAILED = 2;
    private static final int SUPERSEDED = 3;

    private static final class Attempt {
        final Thread owner;
        final String[] configuration;
        int outcome = RUNNING;
        String[] manifest;
        Throwable failure;
        Attempt nextRunning;

        Attempt(String[] configuration) {
            this.owner = Thread.currentThread();
            this.configuration = configuration;
        }
    }

    private static final class User {
        final String name;
        String[] configuration;
        long generation = 1;
        State state = State.PENDING;
        String[] manifest;
        Attempt current;
        Attempt running;
        User next;

        User(String name, String[] configuration, User next) {
            this.name = name;
            this.configuration = configuration;
            this.next = next;
        }
    }

    private final Object monitor = new Object();
    private final Installer installer;
    private User users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        checkName(user);
        String[] copy = copyConfiguration(configuration);
        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("user already registered: " + user);
            }
            users = new User(user, copy, users);
        }
    }

    public String[] activate(String user) throws InterruptedException {
        checkName(user);
        User entry;
        Attempt attempt;
        synchronized (monitor) {
            entry = requireUser(user);
            checkNotRecursive(entry);
            if (entry.state == State.ACTIVE) {
                return entry.manifest.clone();
            }
            if (entry.state == State.FAILED) {
                throw failure(entry.current.failure);
            }
            if (entry.state == State.INSTALLING) {
                attempt = entry.current;
                while (attempt.outcome == RUNNING) {
                    monitor.wait();
                }
                return attemptResult(attempt);
            }
            attempt = new Attempt(entry.configuration);
            startAttempt(entry, attempt);
        }
        return install(entry, attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        checkName(user);
        String[] copy = copyConfiguration(configuration);
        User entry;
        Attempt attempt;
        synchronized (monitor) {
            entry = requireUser(user);
            checkNotRecursive(entry);
            if (entry.generation == 0x7fffffffffffffffL) {
                throw new IllegalStateException("generation overflow");
            }
            attempt = new Attempt(copy);
            if (entry.state == State.INSTALLING) {
                entry.current.outcome = SUPERSEDED;
            }
            entry.generation++;
            entry.configuration = copy;
            entry.manifest = null;
            startAttempt(entry, attempt);
            monitor.notifyAll();
        }
        return install(entry, attempt);
    }

    public Verification verify(String user) {
        checkName(user);
        synchronized (monitor) {
            User entry = requireUser(user);
            String[] manifest = entry.manifest == null ? null : entry.manifest.clone();
            return new Verification(entry.state, entry.generation, manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (monitor) {
            long pending = 0;
            long installing = 0;
            long active = 0;
            long failed = 0;
            long total = 0;
            for (User entry = users; entry != null; entry = entry.next) {
                total++;
                switch (entry.state) {
                    case PENDING: pending++; break;
                    case INSTALLING: installing++; break;
                    case ACTIVE: active++; break;
                    case FAILED: failed++; break;
                }
            }
            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private String[] install(User entry, Attempt attempt) {
        String[] manifest = null;
        Throwable problem = null;
        try {
            manifest = copyManifest(installer.install(
                    entry.name, attempt.configuration.clone()));
        } catch (Throwable ex) {
            if (ex instanceof RuntimeException || ex instanceof Error) {
                problem = ex;
            } else {
                problem = new IllegalStateException("installer failed", ex);
            }
        }

        synchronized (monitor) {
            removeRunning(entry, attempt);
            if (attempt.outcome == SUPERSEDED) {
                throw new IllegalStateException("installation superseded");
            }
            if (problem == null) {
                attempt.manifest = manifest;
                attempt.outcome = SUCCEEDED;
                entry.manifest = manifest;
                entry.state = State.ACTIVE;
            } else {
                attempt.failure = problem;
                attempt.outcome = ATTEMPT_FAILED;
                entry.state = State.FAILED;
            }
            monitor.notifyAll();
            return attemptResult(attempt);
        }
    }

    private void startAttempt(User entry, Attempt attempt) {
        attempt.nextRunning = entry.running;
        entry.running = attempt;
        entry.current = attempt;
        entry.state = State.INSTALLING;
    }

    private void removeRunning(User entry, Attempt attempt) {
        Attempt previous = null;
        Attempt current = entry.running;
        while (current != attempt) {
            previous = current;
            current = current.nextRunning;
        }
        if (previous == null) {
            entry.running = current.nextRunning;
        } else {
            previous.nextRunning = current.nextRunning;
        }
        current.nextRunning = null;
    }

    private static String[] attemptResult(Attempt attempt) {
        if (attempt.outcome == SUPERSEDED) {
            throw new IllegalStateException("installation superseded");
        }
        if (attempt.outcome == ATTEMPT_FAILED) {
            throw failure(attempt.failure);
        }
        return attempt.manifest.clone();
    }

    private static RuntimeException failure(Throwable problem) {
        if (problem instanceof Error) {
            throw (Error) problem;
        }
        return (RuntimeException) problem;
    }

    private void checkNotRecursive(User entry) {
        Thread thread = Thread.currentThread();
        for (Attempt attempt = entry.running; attempt != null;
             attempt = attempt.nextRunning) {
            if (attempt.owner == thread) {
                throw new IllegalStateException(
                        "installer cannot recursively activate or reinstall its user");
            }
        }
    }

    private User requireUser(String name) {
        User entry = findUser(name);
        if (entry == null) {
            throw new IllegalArgumentException("unknown user: " + name);
        }
        return entry;
    }

    private User findUser(String name) {
        for (User entry = users; entry != null; entry = entry.next) {
            if (entry.name.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private static void checkName(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("user must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        String[] copy = new String[configuration.length];
        for (int i = 0; i < configuration.length; i++) {
            String value = configuration[i];
            if (value == null) {
                throw new IllegalArgumentException("configuration entry must not be null");
            }
            copy[i] = value;
        }
        return copy;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("installer returned a null manifest");
        }
        String[] copy = new String[manifest.length];
        for (int i = 0; i < manifest.length; i++) {
            String value = manifest[i];
            if (value == null) {
                throw new IllegalStateException("installer returned a null manifest entry");
            }
            copy[i] = value;
        }
        return copy;
    }
}
