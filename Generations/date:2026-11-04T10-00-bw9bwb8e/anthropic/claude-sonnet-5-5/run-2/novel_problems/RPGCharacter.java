@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;

    private static final int MAX_HEALTH = 100;
    private static final int HEAL_AMOUNT = 10;
    private static final long KILL_REWARD = 10L;
    private static final long XP_PER_LEVEL = 100L;

    private final long id;
    private final String name;

    // Guarded by this
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
                    + ", level=" + level + ", xp=" + experience + ", alive=" + alive + "}";
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

        public int getDamageApplied() { return damageApplied; }
        public boolean causedDeath() { return causedDeath; }
        public Snapshot getAttacker() { return attacker; }
        public Snapshot getTarget() { return target; }
    }

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (name.isEmpty()) throw new IllegalArgumentException("name is empty");
        if (health < 1 || health > MAX_HEALTH) throw new IllegalArgumentException("bad health");
        if (attackPower < 0) throw new IllegalArgumentException("bad attack");
        if (defense < 0) throw new IllegalArgumentException("bad defense");
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
        synchronized (ID_LOCK) {
            if (nextId == Long.MAX_VALUE) {
                throw new IllegalStateException("character ids exhausted");
            }
            this.id = nextId++;
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(name, id, health, attackPower, defense, level, experience);
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    /** Returns {level, experience, attack, defense}; throws ArithmeticException on overflow. Pure. */
    private long[] computeGain(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("experience overflow");
        }
        long total = experience + amount;
        long levels = total / XP_PER_LEVEL;
        long rem = total % XP_PER_LEVEL;
        long newLevel = (long) level + levels;
        long newAttack = (long) attackPower + 2L * levels;
        long newDefense = (long) defense + levels;
        if (newLevel > Integer.MAX_VALUE || newAttack > Integer.MAX_VALUE
                || newDefense > Integer.MAX_VALUE || levels > Long.MAX_VALUE / 2) {
            throw new ArithmeticException("level or stat overflow");
        }
        return new long[] { newLevel, rem, newAttack, newDefense };
    }

    private void applyGain(long[] g) {
        level = (int) g[0];
        experience = g[1];
        attackPower = (int) g[2];
        defense = (int) g[3];
    }

    public void gainExperience(long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative experience");
        synchronized (this) {
            if (health <= 0) throw new IllegalStateException("character is dead");
            long[] g = computeGain(amount);
            applyGain(g);
        }
    }

    public int heal() {
        synchronized (this) {
            if (health <= 0) return 0;
            health = Math.min(MAX_HEALTH, health + HEAL_AMOUNT);
            return health;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) throw new IllegalArgumentException("target is null");
        if (other == this) throw new IllegalArgumentException("self-attack");
        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = first == this ? other : this;
        synchronized (first) {
            synchronized (second) {
                if (this.health <= 0 || other.health <= 0) {
                    return new EncounterResult(0, false, this.snapshotLocked(), other.snapshotLocked());
                }
                long raw = Math.max(0L, (long) this.attackPower - (long) other.defense);
                int applied = (int) Math.min(raw, (long) other.health);
                boolean kills = applied > 0 && other.health - applied == 0;
                long[] gain = null;
                if (kills) {
                    gain = this.computeGain(KILL_REWARD); // may throw before any mutation
                }
                other.health -= applied;
                if (kills) {
                    this.applyGain(gain);
                }
                return new EncounterResult(applied, kills, this.snapshotLocked(), other.snapshotLocked());
            }
        }
    }
}
