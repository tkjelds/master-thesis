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
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (health < 1 || health > 100) {
            throw new IllegalArgumentException("health must be in 1..100");
        }
        if (attackPower < 0) {
            throw new IllegalArgumentException("attackPower must be nonnegative");
        }
        if (defense < 0) {
            throw new IllegalArgumentException("defense must be nonnegative");
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
                throw new IllegalStateException("character ID space exhausted");
            }

            long allocated = nextId;
            if (nextId == Long.MAX_VALUE) {
                idsExhausted = true;
            } else {
                nextId++;
            }
            return allocated;
        }
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (other == this) {
            throw new IllegalArgumentException("a character cannot attack itself");
        }

        RPGCharacter first = id < other.id ? this : other;
        RPGCharacter second = id < other.id ? other : this;
        synchronized (first) {
            synchronized (second) {
                if (health == 0 || other.health == 0) {
                    Snapshot attackerSnapshot = snapshotWhileLocked();
                    Snapshot targetSnapshot = other.snapshotWhileLocked();
                    return new EncounterResult(
                            0, false, attackerSnapshot, targetSnapshot);
                }

                int rawDamage = attackPower > other.defense
                        ? attackPower - other.defense
                        : 0;
                int damage = rawDamage >= other.health
                        ? other.health
                        : rawDamage;
                int resultingHealth = other.health - damage;
                boolean causedDeath = other.health > 0 && resultingHealth == 0;

                ExperiencePlan rewardPlan = null;
                if (causedDeath) {
                    rewardPlan = planExperience(10L);
                }

                int resultingAttackPower = rewardPlan == null
                        ? attackPower : rewardPlan.attackPower;
                int resultingDefense = rewardPlan == null
                        ? defense : rewardPlan.defense;
                int resultingLevel = rewardPlan == null
                        ? level : rewardPlan.level;
                long resultingExperience = rewardPlan == null
                        ? experience : rewardPlan.experience;

                Snapshot attackerSnapshot = new Snapshot(
                        name, id, health, resultingAttackPower, resultingDefense,
                        resultingLevel, resultingExperience, true);
                Snapshot targetSnapshot = new Snapshot(
                        other.name, other.id, resultingHealth, other.attackPower,
                        other.defense, other.level, other.experience,
                        resultingHealth > 0);
                EncounterResult result = new EncounterResult(
                        damage, causedDeath, attackerSnapshot, targetSnapshot);

                other.health = resultingHealth;
                if (rewardPlan != null) {
                    applyExperiencePlan(rewardPlan);
                }
                return result;
            }
        }
    }

    public int heal() {
        synchronized (this) {
            if (health == 0) {
                return 0;
            }
            if (health <= 90) {
                health += 10;
            } else {
                health = 100;
            }
            return health;
        }
    }

    public void gainExperience(long amount) {
        synchronized (this) {
            if (health == 0) {
                throw new IllegalStateException(
                        "a dead character cannot gain experience");
            }
            if (amount < 0L) {
                throw new IllegalArgumentException(
                        "amount must be nonnegative");
            }
            ExperiencePlan plan = planExperience(amount);
            applyExperiencePlan(plan);
        }
    }

    public Snapshot snapshot() {
        synchronized (this) {
            return snapshotWhileLocked();
        }
    }

    private Snapshot snapshotWhileLocked() {
        return new Snapshot(
                name, id, health, attackPower, defense, level, experience,
                health > 0);
    }

    private ExperiencePlan planExperience(long amount) {
        long gainedLevels = amount / 100L;
        long remainderTotal = experience + amount % 100L;
        gainedLevels += remainderTotal / 100L;
        long resultingExperience = remainderTotal % 100L;

        long availableLevels = (long) Integer.MAX_VALUE - level;
        if (gainedLevels > availableLevels) {
            throw new ArithmeticException("level overflow");
        }
        if (gainedLevels > ((long) Integer.MAX_VALUE - attackPower) / 2L) {
            throw new ArithmeticException("attack power overflow");
        }
        if (gainedLevels > (long) Integer.MAX_VALUE - defense) {
            throw new ArithmeticException("defense overflow");
        }

        int resultingLevel = (int) (level + gainedLevels);
        int resultingAttackPower =
                (int) (attackPower + gainedLevels * 2L);
        int resultingDefense = (int) (defense + gainedLevels);
        return new ExperiencePlan(
                resultingLevel, resultingAttackPower, resultingDefense,
                resultingExperience);
    }

    private void applyExperiencePlan(ExperiencePlan plan) {
        level = plan.level;
        attackPower = plan.attackPower;
        defense = plan.defense;
        experience = plan.experience;
    }

    private static final class ExperiencePlan {
        private final int level;
        private final int attackPower;
        private final int defense;
        private final long experience;

        private ExperiencePlan(
                int level, int attackPower, int defense, long experience) {
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
                String name, long id, int health, int attackPower, int defense,
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
        public final int damage;
        public final boolean causedDeath;
        public final Snapshot attackerSnapshot;
        public final Snapshot targetSnapshot;

        private EncounterResult(
                int damage, boolean causedDeath, Snapshot attackerSnapshot,
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
