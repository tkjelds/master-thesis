public class UserSpaceProvisioner {
    public interface Installer {
        String[] install(String user, String[] configuration);
    }

 public enum UserState {
     PENDING,
     INSTALLING,
     ACTIVE,
     FAILED
 }

 public static final class UserSnapshot {
     public final UserState status;
     public final long generation;
     public final String[] manifest;

     private UserSnapshot(UserState status, long generation, String[] manifest) {
         this.status = status;
         this.generation = generation;
         this.manifest = copyArray(manifest);
     }

     public String[] getManifest() {
         return copyArray(manifest);
     }
 }

 public static final class Metrics {
     public final int pending;
     public final int installing;
     public final int active;
     public final int failed;
     public final int totalUsers;

     private Metrics(int pending, int installing, int active, int failed, int totalUsers) {
         this.pending = pending;
         this.installing = installing;
         this.active = active;
         this.failed = failed;
         this.totalUsers = totalUsers;
     }
 }

 private static final int ATTEMPT_RUNNING = 0;
 private static final int ATTEMPT_SUCCEEDED = 1;
 private static final int ATTEMPT_FAILED = 2;
 private static final int ATTEMPT_SUPERSEDED = 3;

 private final Object lock = new Object();
 private final Installer installer;
 private UserRecord users;

 public UserSpaceProvisioner(Installer installer) {
     if (installer == null) {
         throw new IllegalArgumentException("installer must not be null");
     }
     this.installer = installer;
 }

 public void register(String user, String[] configuration) {
     validateUsername(user);
     String[] copiedConfiguration = copyConfiguration(configuration);

     synchronized (lock) {
         if (findUser(user) != null) {
             throw new IllegalArgumentException("User is already registered: " + user);
         }

         UserRecord record = new UserRecord(user, copiedConfiguration);
         record.next = users;
         users = record;
     }
 }

 public String[] activate(String user) throws InterruptedException {
     validateUsername(user);

     UserRecord record;
     Attempt attempt;
     boolean runInstaller = false;

     synchronized (lock) {
         record = requireUser(user);
         rejectCallbackRecursion(record);

         if (record.state == UserState.ACTIVE) {
             return copyArray(record.manifest);
         }
         if (record.state == UserState.FAILED) {
             throwFailure(record.failure);
         }

         if (record.state == UserState.PENDING) {
             attempt = startAttempt(record);
             runInstaller = true;
         } else {
             attempt = record.currentAttempt;
         }
     }

     if (runInstaller) {
         executeAttempt(record, attempt);
     } else {
         awaitAttempt(attempt);
     }

     return attemptResult(attempt);
 }

 public String[] reinstall(String user, String[] configuration) {
     validateUsername(user);
     String[] copiedConfiguration = copyConfiguration(configuration);

     UserRecord record;
     Attempt attempt;

     synchronized (lock) {
         record = requireUser(user);
         rejectCallbackRecursion(record);

         if (record.generation == Long.MAX_VALUE) {
             throw new IllegalStateException("User generation overflow: " + user);
         }

         if (record.state == UserState.INSTALLING) {
             supersede(record.currentAttempt);
         }

         record.generation++;
         record.configuration = copiedConfiguration;
         record.manifest = null;
         record.failure = null;
         record.state = UserState.PENDING;
         attempt = startAttempt(record);
     }

     executeAttempt(record, attempt);
     return attemptResult(attempt);
 }

 public UserSnapshot verify(String user) {
     validateUsername(user);

     synchronized (lock) {
         UserRecord record = requireUser(user);
         return new UserSnapshot(record.state, record.generation, record.manifest);
     }
 }

 public Metrics snapshotMetrics() {
     synchronized (lock) {
         int pending = 0;
         int installing = 0;
         int active = 0;
         int failed = 0;
         int total = 0;

         for (UserRecord record = users; record != null; record = record.next) {
             total++;
             switch (record.state) {
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
                     throw new IllegalStateException("Unknown user state");
             }
         }

         return new Metrics(pending, installing, active, failed, total);
     }
 }

 private Attempt startAttempt(UserRecord record) {
     Attempt attempt = new Attempt(record.generation, Thread.currentThread());
     attempt.callbackNext = record.callbacks;
     record.callbacks = attempt;
     record.currentAttempt = attempt;
     record.state = UserState.INSTALLING;
     return attempt;
 }

 private void executeAttempt(UserRecord record, Attempt attempt) {
     String[] manifest = null;
     Throwable failure = null;

     try {
         manifest = installer.install(record.name, copyArray(record.configuration));
         if (!isValidManifest(manifest)) {
             throw new IllegalStateException("Installer returned an invalid manifest");
         }
         manifest = copyArray(manifest);
     } catch (RuntimeException exception) {
         failure = exception;
     } catch (Error error) {
         failure = error;
     }

     synchronized (lock) {
         if (attempt.result == ATTEMPT_RUNNING) {
             if (record.currentAttempt == attempt
                     && record.generation == attempt.generation
                     && record.state == UserState.INSTALLING) {
                 if (failure == null) {
                     record.manifest = copyArray(manifest);
                     record.failure = null;
                     record.state = UserState.ACTIVE;
                     attempt.manifest = copyArray(manifest);
                     attempt.result = ATTEMPT_SUCCEEDED;
                 } else {
                     record.manifest = null;
                     record.failure = failure;
                     record.state = UserState.FAILED;
                     attempt.failure = failure;
                     attempt.result = ATTEMPT_FAILED;
                 }
             } else {
                 supersede(attempt);
             }
         }

         removeCallback(record, attempt);
         lock.notifyAll();
     }
 }

 private void awaitAttempt(Attempt attempt) throws InterruptedException {
     synchronized (lock) {
         while (attempt.result == ATTEMPT_RUNNING) {
             lock.wait();
         }
     }
 }

 private String[] attemptResult(Attempt attempt) {
     synchronized (lock) {
         if (attempt.result == ATTEMPT_SUCCEEDED) {
             return copyArray(attempt.manifest);
         }
         if (attempt.result == ATTEMPT_FAILED || attempt.result == ATTEMPT_SUPERSEDED) {
             throwFailure(attempt.failure);
         }
         throw new IllegalStateException("Installation attempt has not completed");
     }
 }

 private void supersede(Attempt attempt) {
     if (attempt != null && attempt.result == ATTEMPT_RUNNING) {
         attempt.failure = new IllegalStateException("Installation attempt was superseded");
         attempt.result = ATTEMPT_SUPERSEDED;
         lock.notifyAll();
     }
 }

 private void rejectCallbackRecursion(UserRecord record) {
     Thread currentThread = Thread.currentThread();
     for (Attempt callback = record.callbacks; callback != null; callback = callback.callbackNext) {
         if (callback.owner == currentThread) {
             throw new IllegalStateException(
                     "Installer callbacks cannot recursively activate or reinstall their user");
         }
     }
 }

 private void removeCallback(UserRecord record, Attempt attempt) {
     Attempt previous = null;
     Attempt callback = record.callbacks;

     while (callback != null && callback != attempt) {
         previous = callback;
         callback = callback.callbackNext;
     }
     if (callback != null) {
         if (previous == null) {
             record.callbacks = callback.callbackNext;
         } else {
             previous.callbackNext = callback.callbackNext;
         }
         callback.callbackNext = null;
     }
 }

 private UserRecord requireUser(String user) {
     UserRecord record = findUser(user);
     if (record == null) {
         throw new IllegalArgumentException("Unknown user: " + user);
     }
     return record;
 }

 private UserRecord findUser(String user) {
     for (UserRecord record = users; record != null; record = record.next) {
         if (record.name.equals(user)) {
             return record;
         }
     }
     return null;
 }

 private static void validateUsername(String user) {
     if (user == null || user.length() == 0) {
         throw new IllegalArgumentException("Username must not be null or empty");
     }
 }

 private static String[] copyConfiguration(String[] configuration) {
     if (configuration == null) {
         throw new IllegalArgumentException("Configuration must not be null");
     }
     for (int i = 0; i < configuration.length; i++) {
         if (configuration[i] == null) {
             throw new IllegalArgumentException("Configuration entries must not be null");
         }
     }
     return copyArray(configuration);
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

 private static final class UserRecord {
     final String name;
     long generation = 1;
     UserState state = UserState.PENDING;
     String[] configuration;
     String[] manifest;
     Throwable failure;
     Attempt currentAttempt;
     Attempt callbacks;
     UserRecord next;

     UserRecord(String name, String[] configuration) {
         this.name = name;
         this.configuration = configuration;
     }
 }

 private static final class Attempt {
     final long generation;
     final Thread owner;
     int result = ATTEMPT_RUNNING;
     String[] manifest;
     Throwable failure;
     Attempt callbackNext;

     Attempt(long generation, Thread owner) {
         this.generation = generation;
         this.owner = owner;
     }
 }

}
