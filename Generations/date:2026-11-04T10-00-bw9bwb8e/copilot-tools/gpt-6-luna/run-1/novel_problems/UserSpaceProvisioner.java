@UserSpaceProvisioner.ThreadSafe
public final class UserSpaceProvisioner {
    @interface ThreadSafe {}

    public interface Installer {
        String[] install(String user, String[] configuration);
    }

    public enum State {
        PENDING,
        INSTALLING,
        ACTIVE,
        FAILED
    }

    public static final class UserSnapshot {
        public final State state;
        public final long generation;
        private final String[] manifest;

        private UserSnapshot(State state, long generation, String[] manifest) {
            this.state = state;
            this.generation = generation;
            this.manifest = manifest == null ? null : copyArray(manifest);
        }

        public String[] getManifest() {
            return manifest == null ? null : copyArray(manifest);
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

    private static final class User {
        final String name;
        State state;
        long generation;
        String[] configuration;
        String[] manifest;
        Throwable failure;
        Attempt currentAttempt;
        Attempt callbacks;
        User next;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
            this.state = State.PENDING;
        }
    }

    private static final class Attempt {
        final User user;
        final long generation;
        final String[] configuration;
        final Thread callbackThread;
        boolean done;
        String[] manifest;
        Throwable failure;
        Attempt nextCallback;

        Attempt(User user, long generation, String[] configuration) {
            this.user = user;
            this.generation = generation;
            this.configuration = configuration;
            this.callbackThread = Thread.currentThread();
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
        validateUserName(user);
        String[] configurationCopy = copyConfiguration(configuration);
        synchronized (monitor) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("User is already registered: " + user);
            }
            User entry = new User(user, configurationCopy);
            entry.next = users;
            users = entry;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        Attempt attemptToRun = null;
        Attempt attemptToJoin = null;
        synchronized (monitor) {
            User entry = requireUser(user);
            rejectCallbackReentry(entry);
            if (entry.state == State.ACTIVE) {
                return copyArray(entry.manifest);
            }
            if (entry.state == State.FAILED) {
                throwFailure(entry.failure);
            }
            if (entry.state == State.INSTALLING) {
                attemptToJoin = entry.currentAttempt;
            } else {
                attemptToRun = startAttempt(entry);
            }
        }
        if (attemptToJoin != null) {
            return awaitAttempt(attemptToJoin);
        }
        return runAttempt(attemptToRun);
    }

    public String[] reinstall(String user, String[] configuration)
            throws InterruptedException {
        validateUserName(user);
        String[] configurationCopy = copyConfiguration(configuration);
        Attempt attempt;
        synchronized (monitor) {
            User entry = requireUser(user);
            rejectCallbackReentry(entry);
            if (entry.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("User generation overflow");
            }
            if (entry.state == State.INSTALLING) {
                supersede(entry.currentAttempt);
            }
            entry.generation++;
            entry.configuration = configurationCopy;
            entry.manifest = null;
            entry.failure = null;
            entry.state = State.INSTALLING;
            attempt = createAttempt(entry);
            entry.currentAttempt = attempt;
            monitor.notifyAll();
        }
        return runAttempt(attempt);
    }

    public UserSnapshot verify(String user) {
        synchronized (monitor) {
            User entry = requireUser(user);
            return new UserSnapshot(entry.state, entry.generation, entry.manifest);
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
                if (entry.state == State.PENDING) {
                    pending++;
                } else if (entry.state == State.INSTALLING) {
                    installing++;
                } else if (entry.state == State.ACTIVE) {
                    active++;
                } else {
                    failed++;
                }
            }
            return new Metrics(pending, installing, active, failed, total);
        }
    }

    private Attempt startAttempt(User user) {
        user.state = State.INSTALLING;
        user.manifest = null;
        user.failure = null;
        Attempt attempt = createAttempt(user);
        user.currentAttempt = attempt;
        return attempt;
    }

    private Attempt createAttempt(User user) {
        Attempt attempt = new Attempt(user, user.generation,
                copyArray(user.configuration));
        attempt.nextCallback = user.callbacks;
        user.callbacks = attempt;
        return attempt;
    }

    private String[] runAttempt(Attempt attempt) throws InterruptedException {
        try {
            String[] manifest;
            try {
                String[] result = installer.install(attempt.user.name,
                        copyArray(attempt.configuration));
                manifest = copyManifest(result);
            } catch (RuntimeException failure) {
                finishFailure(attempt, failure);
                return awaitAttempt(attempt);
            } catch (Error failure) {
                finishFailure(attempt, failure);
                return awaitAttempt(attempt);
            }
            finishSuccess(attempt, manifest);
            return awaitAttempt(attempt);
        } finally {
            removeCallback(attempt);
        }
    }

    private void finishSuccess(Attempt attempt, String[] manifest) {
        synchronized (monitor) {
            if (attempt.done) {
                return;
            }
            User user = attempt.user;
            if (user.currentAttempt != attempt
                    || user.generation != attempt.generation) {
                supersede(attempt);
                return;
            }
            user.manifest = manifest;
            user.failure = null;
            user.state = State.ACTIVE;
            attempt.manifest = manifest;
            attempt.done = true;
            monitor.notifyAll();
        }
    }

    private void finishFailure(Attempt attempt, Throwable failure) {
        synchronized (monitor) {
            if (attempt.done) {
                return;
            }
            User user = attempt.user;
            if (user.currentAttempt != attempt
                    || user.generation != attempt.generation) {
                supersede(attempt);
                return;
            }
            user.manifest = null;
            user.failure = failure;
            user.state = State.FAILED;
            attempt.failure = failure;
            attempt.done = true;
            monitor.notifyAll();
        }
    }

    private String[] awaitAttempt(Attempt attempt) throws InterruptedException {
        synchronized (monitor) {
            while (!attempt.done) {
                monitor.wait();
            }
            if (attempt.failure != null) {
                throwFailure(attempt.failure);
            }
            return copyArray(attempt.manifest);
        }
    }

    private void supersede(Attempt attempt) {
        if (attempt != null && !attempt.done) {
            attempt.failure = new IllegalStateException(
                    "Installation attempt was superseded by a newer generation");
            attempt.done = true;
            monitor.notifyAll();
        }
    }

    private void removeCallback(Attempt attempt) {
        synchronized (monitor) {
            User user = attempt.user;
            Attempt previous = null;
            for (Attempt current = user.callbacks; current != null;
                    current = current.nextCallback) {
                if (current == attempt) {
                    if (previous == null) {
                        user.callbacks = current.nextCallback;
                    } else {
                        previous.nextCallback = current.nextCallback;
                    }
                    return;
                }
                previous = current;
            }
        }
    }

    private void rejectCallbackReentry(User user) {
        Thread currentThread = Thread.currentThread();
        for (Attempt attempt = user.callbacks; attempt != null;
                attempt = attempt.nextCallback) {
            if (attempt.callbackThread == currentThread) {
                throw new IllegalStateException(
                        "Installer callbacks may not activate or reinstall their own user");
            }
        }
    }

    private User requireUser(String name) {
        User user = findUser(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }

    private User findUser(String name) {
        for (User user = users; user != null; user = user.next) {
            if (user.name.equals(name)) {
                return user;
            }
        }
        return null;
    }

    private static void validateUserName(String user) {
        if (user == null || user.length() == 0) {
            throw new IllegalArgumentException("User name must not be null or empty");
        }
    }

    private static String[] copyConfiguration(String[] configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration must not be null");
        }
        for (int i = 0; i < configuration.length; i++) {
            if (configuration[i] == null) {
                throw new IllegalArgumentException(
                        "Configuration entries must not be null");
            }
        }
        return copyArray(configuration);
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("Installer returned a null manifest");
        }
        for (int i = 0; i < manifest.length; i++) {
            if (manifest[i] == null) {
                throw new IllegalStateException(
                        "Installer returned a manifest with a null entry");
            }
        }
        return copyArray(manifest);
    }

    private static String[] copyArray(String[] source) {
        if (source == null) {
            return null;
        }
        String[] copy = new String[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("Installation failed", failure);
    }
}
