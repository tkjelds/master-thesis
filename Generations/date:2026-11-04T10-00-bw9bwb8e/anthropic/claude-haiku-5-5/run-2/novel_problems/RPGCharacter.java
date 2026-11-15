@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}


    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted = false;

    private static final int MAX_HEALTH = 100;
    private static final int HEAL_AMOUNT = 10;
    private static final long KILL_REWARD = 10L;
    private static final long POINTS_PER_LEVEL = 100L;

    private final long id;
    private final String name;

    // Mutable state, guarded by this object's monitor.
    private int health;
    private int attackPower;
    private int defense;
    private long level;
    private long experience;

    public static final class Snapshot {
        private final String name;
        private final long id;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final long level;
        private final long experience;
        private final boolean alive;

        private Snapshot(String name, long id, int health, int attackPower,
                         int defense, long level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }

        public String getName() { return name; }
        public long getId() { return id; }
        public int getHealth() { return health; }
        public int getAttackPower() { return attackPower; }
        public int getDefense() { return defense; }
        public long getLevel() { return level; }
        public long getExperience() { return experience; }
        public boolean isAlive() { return alive; }

        @Override
        public String toString() {
            return "Snapshot[name=" + name + ", id=" + id + ", health=" + health
                    + ", attack=" + attackPower + ", defense=" + defense
                    + ", level=" + level + ", experience=" + experience
                    + ", alive=" + alive + "]";
        }
    }

    public static final class EncounterResult {
        private final int appliedDamage;
        private final boolean causedDeath;
        private final Snapshot attacker;
        private final Snapshot target;

        private EncounterResult(int appliedDamage, boolean causedDeath,
                                Snapshot attacker, Snapshot target) {
            this.appliedDamage = appliedDamage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }

        public int getAppliedDamage() { return appliedDamage; }
        public boolean causedDeath() { return causedDeath; }
        public Snapshot getAttacker() { return attacker; }
        public Snapshot getTarget() { return target; }
    }

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name must not be null");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > MAX_HEALTH) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0) {
            throw new IllegalArgumentException("attack power must be nonnegative");
        }
        if (defense < 0) {
            throw new IllegalArgumentException("defense must be nonnegative");
        }

        long allocated;
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("character ID space exhausted");
            }
            allocated = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
        }

        this.id = allocated;
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1L;
        this.experience = 0L;
    }

    /**
     * Performs one atomic encounter in which this character attacks other.
     * Locks are acquired in ascending ID order to prevent deadlock.
     */
    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("self-attacks are not allowed");
        }

        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = (first == this) ? other : this;

        synchronized (first) {
            synchronized (second) {
                RPGCharacter attacker = this;
                RPGCharacter target = other;

                if (attacker.health == 0 || target.health == 0) {
                    return new EncounterResult(0, false,
                            attacker.snapshotLocked(), target.snapshotLocked());
                }

                long raw = (long) attacker.attackPower - (long) target.defense;
                long damage = raw > 0 ? raw : 0L;
                long applied = Math.min(damage, (long) target.health);
                int newHealth = target.health - (int) applied;
                boolean killing = newHealth == 0;

                // Validate the killing blow reward before any mutation.
                if (killing) {
                    attacker.checkGain(KILL_REWARD);
                }

                // Commit damage and reward together.
                target.health = newHealth;
                if (killing) {
                    attacker.applyGain(KILL_REWARD);
                }

                return new EncounterResult((int) applied, killing,
                        attacker.snapshotLocked(), target.snapshotLocked());
            }
        }
    }

    /**
     * Restores up to 10 health, capped at 100. Returns the resulting health.
     * A dead character stays at zero.
     */
    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            health = Math.min(MAX_HEALTH, health + HEAL_AMOUNT);
            return health;
        }
    }

    /**
     * Grants nonnegative experience to a living character, applying any
     * level-ups. All overflow checks happen before any mutation.
     */
    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("experience amount must be nonnegative");
        }
        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("dead characters cannot gain experience");
            }
            checkGain(amount);
            applyGain(amount);
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    // Must be called while holding this object's monitor.
    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense,
                level, experience, health > 0);
    }

    // Must be called while holding this object's monitor.
    // Verifies that applyGain(amount) cannot overflow; mutates nothing.
    private void checkGain(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("experience overflow");
        }
        long total = experience + amount;
        long gained = total / POINTS_PER_LEVEL;
        if (gained > Long.MAX_VALUE - level) {
            throw new ArithmeticException("level overflow");
        }
        if ((long) attackPower + 2L * gained > Integer.MAX_VALUE) {
            throw new ArithmeticException("attack power overflow");
        }
        if ((long) defense + gained > Integer.MAX_VALUE) {
            throw new ArithmeticException("defense overflow");
        }
    }

    // Must be called while holding this object's monitor, after checkGain succeeded.
    private void applyGain(long amount) {
        long total = experience + amount;
        long gained = total / POINTS_PER_LEVEL;
        experience = total % POINTS_PER_LEVEL;
        level += gained;
        attackPower += (int) (2L * gained);
        defense += (int) gained;
    }
}
