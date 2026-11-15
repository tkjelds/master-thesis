import java.lang.*;

@RPGCharacter.ThreadSafe
public class RPGCharacter {
    @interface ThreadSafe {}


    private static final int MAX_HEALTH = 100;
    private static final int HEAL_AMOUNT = 10;
    private static final long KILL_REWARD = 10L;
    private static final long XP_PER_LEVEL = 100L;
    private static final int ATTACK_PER_LEVEL = 2;
    private static final int DEFENSE_PER_LEVEL = 1;

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
        private final int damage;
        private final boolean causedDeath;
        private final Snapshot attacker;
        private final Snapshot target;

        private EncounterResult(int damage, boolean causedDeath, Snapshot attacker, Snapshot target) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attacker = attacker;
            this.target = target;
        }

        public int getDamage() { return damage; }
        public boolean causedDeath() { return causedDeath; }
        public Snapshot getAttacker() { return attacker; }
        public Snapshot getTarget() { return target; }
    }

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
            this.id = nextId;
            nextId++;
        }
    }

    // Must be called holding this monitor.
    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience);
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    // Computes the result of gaining xp; returns {level, attack, defense} as longs plus exp.
    // Throws without mutating. Must hold this monitor.
    private long[] computeGain(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("experience overflow");
        }
        long total = experience + amount;
        long levels = total / XP_PER_LEVEL;
        long rem = total % XP_PER_LEVEL;
        long newLevel = (long) level + levels;
        if (newLevel > Integer.MAX_VALUE || newLevel < 0) {
            throw new ArithmeticException("level overflow");
        }
        // levels <= ~9.2e16, so multiplication by 2 fits in long
        long newAttack = (long) attackPower + levels * ATTACK_PER_LEVEL;
        long newDefense = (long) defense + levels * DEFENSE_PER_LEVEL;
        if (newAttack > Integer.MAX_VALUE || newAttack < 0) {
            throw new ArithmeticException("attack overflow");
        }
        if (newDefense > Integer.MAX_VALUE || newDefense < 0) {
            throw new ArithmeticException("defense overflow");
        }
        return new long[] { newLevel, newAttack, newDefense, rem };
    }

    private void applyGain(long[] r) {
        level = (int) r[0];
        attackPower = (int) r[1];
        defense = (int) r[2];
        experience = r[3];
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be nonnegative");
        }
        synchronized (this) {
            if (health <= 0) {
                throw new IllegalStateException("character is dead");
            }
            long[] r = computeGain(amount);
            applyGain(r);
        }
    }

    public int heal() {
        synchronized (this) {
            if (health <= 0) {
                return 0;
            }
            int h = health + HEAL_AMOUNT;
            if (h > MAX_HEALTH) {
                h = MAX_HEALTH;
            }
            health = h;
            return health;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("cannot attack self");
        }
        RPGCharacter first;
        RPGCharacter second;
        if (this.id < other.id) {
            first = this;
            second = other;
        } else {
            first = other;
            second = this;
        }
        synchronized (first) {
            synchronized (second) {
                if (this.health <= 0 || other.health <= 0) {
                    return new EncounterResult(0, false, this.snapshotLocked(), other.snapshotLocked());
                }
                long raw = (long) this.attackPower - (long) other.defense;
                int dmg = raw <= 0 ? 0 : (raw > other.health ? other.health : (int) raw);
                boolean kills = dmg > 0 && dmg >= other.health;
                long[] reward = null;
                if (kills) {
                    // Check everything before mutating anything.
                    reward = this.computeGain(KILL_REWARD);
                }
                other.health -= dmg;
                if (kills) {
                    this.applyGain(reward);
                }
                return new EncounterResult(dmg, kills, this.snapshotLocked(), other.snapshotLocked());
            }
        }
    }
}
