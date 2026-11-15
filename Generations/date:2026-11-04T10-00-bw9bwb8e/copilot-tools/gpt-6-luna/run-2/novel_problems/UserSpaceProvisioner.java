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
        public final State status;
        public final long generation;
        public final String[] manifest;

        private UserSnapshot(State status, long generation, String[] manifest) {
            this.status = status;
            this.generation = generation;
            this.manifest = copy(manifest);
        }
    }

    public static final class Metrics {
        public final int pendingCount;
        public final int installingCount;
        public final int activeCount;
        public final int failedCount;
        public final int totalUsers;

        private Metrics(int pendingCount, int installingCount, int activeCount,
                        int failedCount, int totalUsers) {
            this.pendingCount = pendingCount;
            this.installingCount = installingCount;
            this.activeCount = activeCount;
            this.failedCount = failedCount;
            this.totalUsers = totalUsers;
        }
    }

    private static final class Attempt {
        final long generation;
        final Thread owner;
        boolean done;
        boolean superseded;
        String[] manifest;
        Throwable failure;

        Attempt(long generation, Thread owner) {
            this.generation = generation;
            this.owner = owner;
        }
    }

    private static final class CallbackThread {
        final Thread thread;
        CallbackThread next;

        CallbackThread(Thread thread) {
            this.thread = thread;
        }
    }

    private static final class User {
        final String name;
        State state = State.PENDING;
        long generation = 1L;
        String[] configuration;
        String[] manifest;
        Throwable failure;
        Attempt attempt;
        CallbackThread callbackThreads;
        User next;

        User(String name, String[] configuration) {
            this.name = name;
            this.configuration = configuration;
        }
    }

    private final Object gate = new Object();
    private final Installer installer;
    private User users;

    public UserSpaceProvisioner(Installer installer) {
        if (installer == null) {
            throw new IllegalArgumentException("installer must not be null");
        }
        this.installer = installer;
    }

    public void register(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        synchronized (gate) {
            if (findUser(user) != null) {
                throw new IllegalArgumentException("user is already registered: " + user);
            }
            User entry = new User(user, copiedConfiguration);
            entry.next = users;
            users = entry;
        }
    }

    public String[] activate(String user) throws InterruptedException {
        validateUser(user);
        Attempt attempt;
        String[] configuration = null;
        boolean install = false;

        synchronized (gate) {
            User entry = requireUser(user);
            if (entry.state == State.ACTIVE) {
                return copy(entry.manifest);
            }
            if (entry.state == State.FAILED) {
                throwFailure(entry.failure);
            }
            if (entry.state == State.PENDING) {
                if (isCallbackThread(entry, Thread.currentThread())) {
                    throw new IllegalStateException(
                            "installer callback cannot recursively activate its user");
                }
                attempt = new Attempt(entry.generation, Thread.currentThread());
                entry.attempt = attempt;
                entry.state = State.INSTALLING;
                configuration = copy(entry.configuration);
                install = true;
            } else {
                attempt = entry.attempt;
                if (isCallbackThread(entry, Thread.currentThread())
                        || attempt.owner == Thread.currentThread()) {
                    throw new IllegalStateException(
                            "installer callback cannot recursively activate its user");
                }
                while (!attempt.done) {
                    gate.wait();
                }
            }
        }

        if (install) {
            performInstallation(user, attempt, configuration);
        }
        return completedResult(attempt);
    }

    public String[] reinstall(String user, String[] configuration) {
        validateUser(user);
        String[] copiedConfiguration = copyConfiguration(configuration);
        Attempt attempt;

        synchronized (gate) {
            User entry = requireUser(user);
            if (isCallbackThread(entry, Thread.currentThread())
                    || (entry.state == State.INSTALLING
                        && entry.attempt.owner == Thread.currentThread())) {
                throw new IllegalStateException(
                        "installer callback cannot recursively reinstall its user");
            }
            if (entry.generation == Long.MAX_VALUE) {
                throw new IllegalStateException("user generation overflow");
            }

            if (entry.attempt != null && !entry.attempt.done) {
                entry.attempt.done = true;
                entry.attempt.superseded = true;
            }
            entry.generation++;
            entry.configuration = copiedConfiguration;
            entry.manifest = null;
            entry.failure = null;
            attempt = new Attempt(entry.generation, Thread.currentThread());
            entry.attempt = attempt;
            entry.state = State.INSTALLING;
            gate.notifyAll();
        }

        performInstallation(user, attempt, copy(copiedConfiguration));
        return completedResult(attempt);
    }

    public UserSnapshot verify(String user) {
        validateUser(user);
        synchronized (gate) {
            User entry = requireUser(user);
            return new UserSnapshot(entry.state, entry.generation, entry.manifest);
        }
    }

    public Metrics snapshotMetrics() {
        synchronized (gate) {
            int pending = 0;
            int installing = 0;
            int active = 0;
            int failed = 0;
            int total = 0;
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

    private void performInstallation(String user, Attempt attempt, String[] configuration) {
        CallbackThread callbackThread;
        synchronized (gate) {
            if (attempt.done) {
                return;
            }
            User entry = findUser(user);
            if (entry == null) {
                attempt.done = true;
                attempt.superseded = true;
                gate.notifyAll();
                return;
            }
            callbackThread = new CallbackThread(Thread.currentThread());
            callbackThread.next = entry.callbackThreads;
            entry.callbackThreads = callbackThread;
        }

        try {
            String[] installed;
            try {
                installed = copyManifest(installer.install(user, copy(configuration)));
            } catch (RuntimeException failure) {
                completeFailure(user, attempt, failure);
                return;
            } catch (Error failure) {
                completeFailure(user, attempt, failure);
                return;
            }

            synchronized (gate) {
                if (!attempt.done) {
                    User entry = findUser(user);
                    if (entry != null && entry.attempt == attempt
                            && entry.generation == attempt.generation
                            && entry.state == State.INSTALLING) {
                        entry.manifest = installed;
                        entry.failure = null;
                        entry.state = State.ACTIVE;
                        attempt.manifest = installed;
                        attempt.done = true;
                    } else {
                        attempt.superseded = true;
                        attempt.done = true;
                    }
                    gate.notifyAll();
                }
            }
        } finally {
            synchronized (gate) {
                User entry = findUser(user);
                if (entry != null) {
                    CallbackThread previous = null;
                    CallbackThread current = entry.callbackThreads;
                    while (current != null && current != callbackThread) {
                        previous = current;
                        current = current.next;
                    }
                    if (current != null) {
                        if (previous == null) {
                            entry.callbackThreads = current.next;
                        } else {
                            previous.next = current.next;
                        }
                    }
                }
            }
        }
    }

    private void completeFailure(String user, Attempt attempt, Throwable failure) {
        synchronized (gate) {
            if (!attempt.done) {
                User entry = findUser(user);
                if (entry != null && entry.attempt == attempt
                        && entry.generation == attempt.generation
                        && entry.state == State.INSTALLING) {
                    entry.manifest = null;
                    entry.failure = failure;
                    entry.state = State.FAILED;
                    attempt.failure = failure;
                } else {
                    attempt.superseded = true;
                }
                attempt.done = true;
                gate.notifyAll();
            }
        }
    }

    private String[] completedResult(Attempt attempt) {
        synchronized (gate) {
            if (attempt.superseded) {
                throw new IllegalStateException("installation attempt was superseded");
            }
            if (attempt.failure != null) {
                throwFailure(attempt.failure);
            }
            if (attempt.manifest == null) {
                throw new IllegalStateException("installation attempt has no result");
            }
            return copy(attempt.manifest);
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

    private static boolean isCallbackThread(User user, Thread thread) {
        for (CallbackThread callback = user.callbackThreads;
             callback != null; callback = callback.next) {
            if (callback.thread == thread) {
                return true;
            }
        }
        return false;
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
        String[] result = copy(configuration);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalArgumentException("configuration entries must not be null");
            }
        }
        return result;
    }

    private static String[] copyManifest(String[] manifest) {
        if (manifest == null) {
            throw new IllegalStateException("installer returned a null manifest");
        }
        String[] result = copy(manifest);
        for (int i = 0; i < result.length; i++) {
            if (result[i] == null) {
                throw new IllegalStateException("installer returned a manifest with a null entry");
            }
        }
        return result;
    }

    private static String[] copy(String[] values) {
        if (values == null) {
            return null;
        }
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("installation failed", failure);
    }
}
