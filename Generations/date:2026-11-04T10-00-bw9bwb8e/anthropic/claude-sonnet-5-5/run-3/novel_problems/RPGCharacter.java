import java.lang.*;

@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}

    private static final int MAX_HEALTH = 100;
    private static final int HEAL_AMOUNT = 10;
    private static final long KILL_REWARD = 10L;
    private static final long EXP_PER_LEVEL = 100L;

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;

    private final long id;
    private final String name;

    // Guarded by this monitor
    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

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
                         int level, long experience) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = health > 0;
        }

        public String getName() { return name; }
        public long getId() { return id; }
        public int getHealth() { return health; }
        public int getAttackPower() { return attackPower; }
        public int getDefense() { return defense; }
        public int getLevel() { return level; }
        public long getExperience() { return experience; }
        public boolean isAlive() { return alive; }

        @Override
        public String toString() {
            return "Snapshot{name=" + name + ", id=" + id + ", health=" + health
                    + ", attack=" + attackPower + ", defense=" + defense
                    + ", level=" + level + ", experience=" + experience
                    + ", alive=" + alive + "}";
        }
    }

    public static final class EncounterResult {
        private final boolean performed;
        private final int damage;
        private final boolean causedDeath;
        private final Snapshot attacker;
        private final Snapshot target;

        private EncounterResult(boolean performed, int damage, boolean causedDeath,
                                Snapshot attacker, Snapshot target) {
            this.performed = performed;
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }

        public boolean isPerformed() { return performed; }
        public boolean isNoOp() { return !performed; }
        public int getDamage() { return damage; }
        public boolean isCausedDeath() { return causedDeath; }
        public Snapshot getAttacker() { return attacker; }
        public Snapshot getTarget() { return target; }
    }

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        if (health < 1 || health > MAX_HEALTH) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attack and defense must be nonnegative");
        }
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
        synchronized (ID_LOCK) {
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("character IDs exhausted");
            }
            this.id = nextId++;
        }
    }

    // Caller must hold this monitor. Returns {newLevel, newAttack, newDefense, newExperience}
    // or throws ArithmeticException without mutating anything.
    private long[] planGain(long amount) {
        long total = experience + amount;
        if (total < 0) { // overflow, since both operands nonnegative
            throw new ArithmeticException("experience overflow");
        }
        long gained = total / EXP_PER_LEVEL;
        long rem = total % EXP_PER_LEVEL;
        long newLevel = (long) level + gained;
        if (newLevel > Integer.MAX_VALUE) {
            throw new ArithmeticException("level overflow");
        }
        // gained <= Integer.MAX_VALUE here, so 2*gained fits in long
        long newAttack = (long) attackPower + 2L * gained;
        long newDefense = (long) defense + gained;
        if (newAttack > Integer.MAX_VALUE || newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("stat overflow");
        }
        return new long[] { newLevel, newAttack, newDefense, rem };
    }

    // Caller must hold this monitor.
    private void applyPlan(long[] plan) {
        level = (int) plan[0];
        attackPower = (int) plan[1];
        defense = (int) plan[2];
        experience = plan[3];
    }

    // Caller must hold this monitor.
    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience);
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("cannot attack self");
        }
        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = first == this ? other : this;
        synchronized (first) {
            synchronized (second) {
                if (this.health <= 0 || other.health <= 0) {
                    return new EncounterResult(false, 0, false,
                            this.snapshotLocked(), other.snapshotLocked());
                }
                long raw = (long) this.attackPower - (long) other.defense;
                if (raw < 0) {
                    raw = 0;
                }
                int applied = (int) Math.min(raw, (long) other.health);
                boolean kills = applied > 0 && other.health - applied == 0;
                long[] plan = null;
                if (kills) {
                    plan = this.planGain(KILL_REWARD); // may throw before any mutation
                }
                other.health -= applied;
                if (kills) {
                    this.applyPlan(plan);
                }
                return new EncounterResult(true, applied, kills,
                        this.snapshotLocked(), other.snapshotLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health <= 0) {
                return 0;
            }
            int h = health + HEAL_AMOUNT;
            health = h > MAX_HEALTH ? MAX_HEALTH : h;
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (this) {
            if (health <= 0) {
                throw new IllegalStateException("character is dead");
            }
            long[] plan = planGain(amount);
            applyPlan(plan);
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }
}
