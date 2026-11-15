@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final int MAX_HEALTH = 100;
    private static final Object ID_LOCK = new Object();
    private static long nextId = 1;
    private static boolean idsExhausted;

    private final String name;
    private final long id;

    private int health;
    private int attackPower;
    private int defense;
    private long level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null) {
            throw new IllegalArgumentException("Name must not be null");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        if (health < 1 || health > MAX_HEALTH) {
            throw new IllegalArgumentException("Health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Attack power and defense must be nonnegative");
        }

        this.name = name;
        this.id = allocateId();
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("Character ID space exhausted");
            }

            long id = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return id;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new IllegalArgumentException("Target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("A character cannot attack itself");
        }

        if (id < other.id) {
            synchronized (this) {
                synchronized (other) {
                    return encounterLocked(other);
                }
            }
        } else {
            synchronized (other) {
                synchronized (this) {
                    return encounterLocked(other);
                }
            }
        }
    }

    private EncounterResult encounterLocked(RPGCharacter target) {
        if (health == 0 || target.health == 0) {
            return new EncounterResult(
                    0,
                    false,
                    snapshotLocked(),
                    target.snapshotLocked());
        }

        int rawDamage = attackPower > target.defense
                ? attackPower - target.defense
                : 0;
        int damage = rawDamage < target.health ? rawDamage : target.health;
        boolean causedDeath = damage > 0 && damage == target.health;

        Progression progression = causedDeath ? calculateProgression(10) : null;

        target.health -= damage;
        if (progression != null) {
            applyProgression(progression);
        }

        return new EncounterResult(
                damage,
                causedDeath,
                snapshotLocked(),
                target.snapshotLocked());
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }

            int amount = MAX_HEALTH - health;
            if (amount > 10) {
                amount = 10;
            }
            health += amount;
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Experience amount must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("A dead character cannot gain experience");
            }

            applyProgression(calculateProgression(amount));
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(
                name,
                id,
                health,
                attackPower,
                defense,
                level,
                experience,
                health > 0);
    }

    private Progression calculateProgression(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("Experience overflow");
        }

        long totalExperience = experience + amount;
        long levelsGained = totalExperience / 100;

        if (levelsGained > Long.MAX_VALUE - level) {
            throw new ArithmeticException("Level overflow");
        }
        if (levelsGained > (Integer.MAX_VALUE - attackPower) / 2L) {
            throw new ArithmeticException("Attack power overflow");
        }
        if (levelsGained > Integer.MAX_VALUE - (long) defense) {
            throw new ArithmeticException("Defense overflow");
        }

        return new Progression(
                level + levelsGained,
                (int) (attackPower + levelsGained * 2),
                (int) (defense + levelsGained),
                totalExperience % 100);
    }

    private void applyProgression(Progression progression) {
        level = progression.level;
        attackPower = progression.attackPower;
        defense = progression.defense;
        experience = progression.experience;
    }

    private static final class Progression {
        private final long level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private Progression(long level, int attackPower, int defense, long experience) {
            this.level = level;
            this.attackPower = attackPower;
            this.defense = defense;
            this.experience = experience;
        }
    }

    public static final class Snapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final long level;
        public final long experience;
        public final boolean alive;

        private Snapshot(
                String name,
                long id,
                int health,
                int attackPower,
                int defense,
                long level,
                long experience,
                boolean alive) {
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

        public long getLevel() {
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
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(
                int damage,
                boolean causedDeath,
                Snapshot attackerSnapshot,
                Snapshot targetSnapshot) {
            this.damage = damage;
            this.causedDeath = causedDeath;
            this.attackerSnapshot = attackerSnapshot;
            this.targetSnapshot = targetSnapshot;
        }

        public int getDamage() {
            return damage;
        }

        public boolean causedDeath() {
            return causedDeath;
        }

        public Snapshot getAttackerSnapshot() {
            return attackerSnapshot;
        }

        public Snapshot getTargetSnapshot() {
            return targetSnapshot;
        }
    }
}
