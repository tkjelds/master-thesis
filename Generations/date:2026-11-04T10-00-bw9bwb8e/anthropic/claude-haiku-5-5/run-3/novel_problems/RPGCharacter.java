@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}


    public static final int MAX_HEALTH = 100;
    public static final int HEAL_AMOUNT = 10;
    public static final long KILL_REWARD = 10L;
    private static final long EXPERIENCE_PER_LEVEL = 100L;
    private static final int LEVEL_ATTACK_BONUS = 2;
    private static final int LEVEL_DEFENSE_BONUS = 1;

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;

    private final String name;
    private final long id;

    // All mutable fields below are guarded by 'this' monitor.
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must be nonempty");
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
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("character ID space exhausted");
            }
            allocated = nextId;
            nextId++;
        }
        this.id = allocated;
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    /**
     * Performs one atomic two-character encounter. Returns a detached result.
     * If either character is dead, a no-op result is returned.
     */
    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("target");
        }
        if (other == this) {
            throw new IllegalArgumentException("self-attack not allowed");
        }

        // Consistent lock ordering by immutable unique ID prevents deadlock.
        RPGCharacter first = (this.id < other.id) ? this : other;
        RPGCharacter second = (first == this) ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (this.health <= 0 || other.health <= 0) {
                    return new EncounterResult(0, false, captureLocked(), other.captureLocked());
                }

                int damage = Math.max(0, this.attackPower - other.defense);
                int oldHealth = other.health;
                int newHealth = Math.max(0, oldHealth - damage);
                int applied = oldHealth - newHealth;
                boolean killed = (oldHealth > 0 && newHealth == 0);

                // Validate the killing-blow reward before any mutation.
                long[] reward = null;
                if (killed) {
                    reward = this.planGain(KILL_REWARD);
                }

                // Commit damage and reward together.
                other.health = newHealth;
                if (reward != null) {
                    this.applyPlan(reward);
                }

                return new EncounterResult(applied, killed, captureLocked(), other.captureLocked());
            }
        }
    }

    /**
     * Gains experience for a living character. Throws IllegalStateException if dead,
     * IllegalArgumentException if amount is negative, and ArithmeticException on overflow.
     * No state changes occur if an exception is thrown.
     */
    public void gainExperience(long amount) {
        synchronized (this) {
            if (amount < 0L) {
                throw new IllegalArgumentException("experience amount must be nonnegative");
            }
            if (health <= 0) {
                throw new IllegalStateException("dead character cannot gain experience");
            }
            long[] plan = planGain(amount);
            applyPlan(plan);
        }
    }

    /**
     * Heals up to HEAL_AMOUNT, capped at MAX_HEALTH. Dead characters stay at zero.
     * Returns resulting health.
     */
    public int heal() {
        synchronized (this) {
            if (health <= 0) {
                return 0;
            }
            health = Math.min(MAX_HEALTH, health + HEAL_AMOUNT);
            return health;
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return captureLocked();
        }
    }

    // Must be called while holding this object's monitor.
    private Snapshot captureLocked() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience, health > 0);
    }

    // Must be called while holding this object's monitor.
    // Computes the new leveled state without mutating anything; throws on overflow.
    private long[] planGain(long amount) {
        long total = Math.addExact(experience, amount);
        long levelsGained = total / EXPERIENCE_PER_LEVEL;
        long newExperience = total % EXPERIENCE_PER_LEVEL;

        long newLevel = (long) level + levelsGained;
        if (newLevel > Integer.MAX_VALUE) {
            throw new ArithmeticException("level overflow");
        }
        long newAttack = (long) attackPower + (long) LEVEL_ATTACK_BONUS * levelsGained;
        if (newAttack > Integer.MAX_VALUE) {
            throw new ArithmeticException("attack power overflow");
        }
        long newDefense = (long) defense + (long) LEVEL_DEFENSE_BONUS * levelsGained;
        if (newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("defense overflow");
        }
        return new long[] { newExperience, newLevel, newAttack, newDefense };
    }

    // Must be called while holding this object's monitor.
    private void applyPlan(long[] plan) {
        this.experience = plan[0];
        this.level = (int) plan[1];
        this.attackPower = (int) plan[2];
        this.defense = (int) plan[3];
    }

    public static final class Snapshot {
        private final String name;
        private final long id;
        private final int health;
        private final int attackPower;
        private final int defense;
        private final int level;
        private final long experience;
        private final boolean alive;

        private Snapshot(String name, long id, int health, int attackPower, int defense,
                         int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }

        public String getName() {
            return name;
        }

        public long getId() {
            return id;
        }

        public int getHealth() {
            return health;
        }

        public int getAttackPower() {
            return attackPower;
        }

        public int getDefense() {
            return defense;
        }

        public int getLevel() {
            return level;
        }

        public long getExperience() {
            return experience;
        }

        public boolean isAlive() {
            return alive;
        }
    }

    public static final class EncounterResult {
        private final int damageApplied;
        private final boolean causedDeath;
        private final Snapshot attacker;
        private final Snapshot target;

        private EncounterResult(int damageApplied, boolean causedDeath,
                                Snapshot attacker, Snapshot target) {
            this.damageApplied = damageApplied;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }

        public int getDamageApplied() {
            return damageApplied;
        }

        public boolean causedDeath() {
            return causedDeath;
        }

        public Snapshot getAttacker() {
            return attacker;
        }

        public Snapshot getTarget() {
            return target;
        }
    }
}
