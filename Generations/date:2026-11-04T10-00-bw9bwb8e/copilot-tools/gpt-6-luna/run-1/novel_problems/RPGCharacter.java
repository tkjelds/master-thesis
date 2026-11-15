@RPGCharacter.ThreadSafe
public final class RPGCharacter {
    @interface ThreadSafe {}

    private static final Object ID_LOCK = new Object();
    private static long nextId = 1L;
    private static boolean idsExhausted;

    private final long id;
    private final String name;

    private int health;
    private int attackPower;
    private int defense;
    private int level;
    private long experience;

    public RPGCharacter(String name, int health, int attackPower, int defense) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("name must be nonempty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("attack power and defense must be nonnegative");
        }

        this.id = allocateId();
        this.name = name;
        this.health = health;
        this.attackPower = attackPower;
        this.defense = defense;
        this.level = 1;
        this.experience = 0L;
    }

    private static long allocateId() {
        synchronized (ID_LOCK) {
            if (idsExhausted) {
                throw new IllegalStateException("character IDs exhausted");
            }

            long id = nextId;
            if (id == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId = id + 1L;
            }
            return id;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (other == this) {
            throw new IllegalArgumentException("a character cannot attack itself");
        }

        RPGCharacter first = this.id < other.id ? this : other;
        RPGCharacter second = this.id < other.id ? other : this;

        synchronized (first) {
            synchronized (second) {
                if (this.health == 0 || other.health == 0) {
                    return new EncounterResult(
                            0,
                            false,
                            snapshotWhileLocked(),
                            other.snapshotWhileLocked());
                }

                int potentialDamage = this.attackPower > other.defense
                        ? this.attackPower - other.defense
                        : 0;
                int appliedDamage = potentialDamage < other.health
                        ? potentialDamage
                        : other.health;
                int resultingHealth = other.health - appliedDamage;
                boolean causedDeath = resultingHealth == 0;

                LevelUpPlan rewardPlan = null;
                if (causedDeath) {
                    rewardPlan = planExperience(10L);
                }

                if (rewardPlan != null) {
                    applyExperiencePlan(rewardPlan);
                }
                other.health = resultingHealth;

                return new EncounterResult(
                        appliedDamage,
                        causedDeath,
                        snapshotWhileLocked(),
                        other.snapshotWhileLocked());
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }

            int missingHealth = 100 - health;
            health += missingHealth < 10 ? missingHealth : 10;
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("experience amount must be nonnegative");
        }

        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException("a dead character cannot gain experience");
            }

            applyExperiencePlan(planExperience(amount));
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotWhileLocked();
        }
    }

    private Snapshot snapshotWhileLocked() {
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

    private LevelUpPlan planExperience(long amount) {
        if (amount > Long.MAX_VALUE - experience) {
            throw new ArithmeticException("experience overflow");
        }

        long totalExperience = experience + amount;
        long levelsGained = totalExperience / 100L;
        long newLevel = (long) level + levelsGained;
        long newAttackPower = (long) attackPower + levelsGained * 2L;
        long newDefense = (long) defense + levelsGained;

        if (newLevel > Integer.MAX_VALUE
                || newAttackPower > Integer.MAX_VALUE
                || newDefense > Integer.MAX_VALUE) {
            throw new ArithmeticException("level or combat stat overflow");
        }

        return new LevelUpPlan(
                (int) newLevel,
                (int) newAttackPower,
                (int) newDefense,
                totalExperience % 100L);
    }

    private void applyExperiencePlan(LevelUpPlan plan) {
        level = plan.level;
        attackPower = plan.attackPower;
        defense = plan.defense;
        experience = plan.experience;
    }

    private static final class LevelUpPlan {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private LevelUpPlan(int level, int attackPower, int defense, long experience) {
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
        public final int level;
        public final long experience;
        public final boolean alive;

        private Snapshot(
                String name,
                long id,
                int health,
                int attackPower,
                int defense,
                int level,
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
