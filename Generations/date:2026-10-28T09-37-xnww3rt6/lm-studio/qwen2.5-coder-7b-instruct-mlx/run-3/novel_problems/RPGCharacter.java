```java
class RPGCharacter {
    private final String name;
    private final long id;
    private int health;
    private final int maxHealth = 100;
    private int attackPower;
    private int defense;
    private int level = 1;
    private long experience = 0;
    private boolean alive;

    public RPGCharacter(String name, int attackPower, int defense) {
        if (name == null || attackPower < 0 || defense < 0) {
            throw new IllegalArgumentException("Invalid parameters");
        }
        this.name = name;
        this.id = System.currentTimeMillis(); // Using current time as unique ID
        this.attackPower = attackPower;
        this.defense = defense;
        this.alive = true;
    }

    public EncounterResult attack(RPGCharacter other) {
        if (other == null || this.equals(other)) {
            throw new IllegalArgumentException("Invalid target");
        }
        if (!this.alive || !other.alive) {
            return new EncounterResult(this, other);
        }

        synchronized (this) {
            if (!this.alive) return new EncounterResult(this, other);

            synchronized (other) {
                if (!this.alive) return new EncounterResult(this, other);
                int damage = Math.max(0, attackPower - other.defense);
                other.health -= damage;
                if (other.health < 0) {
                    other.health = 0;
                    this.gainExperience(10);
                }
            }
        }

        return new EncounterResult(this, other);
    }

    public int heal() {
        if (!alive) return 0;

        synchronized (this) {
            health = Math.min(maxHealth, health + 10);
            return health;
        }
    }

    public void gainExperience(long amount) {
        if (!alive) throw new IllegalStateException("Dead character cannot gain experience");

        synchronized (this) {
            experience += amount;
            while (experience >= 100) {
                level++;
                attackPower += 2;
                defense += 1;
                experience -= 100;
            }
        }
    }

    public CharacterSnapshot snapshot() {
        return new CharacterSnapshot(name, id, health, attackPower, defense, level, experience, alive);
    }

    private static class EncounterResult {
        private final RPGCharacter attacker;
        private final RPGCharacter defender;

        public EncounterResult(RPGCharacter attacker, RPGCharacter defender) {
            this.attacker = attacker;
            this.defender = defender;
        }

        public CharacterSnapshot getAttackerSnapshot() {
            return attacker.snapshot();
        }

        public CharacterSnapshot getDefenderSnapshot() {
            return defender.snapshot();
        }
    }

    private static class CharacterSnapshot {
        public final String name;
        public final long id;
        public final int health;
        public final int attackPower;
        public final int defense;
        public final int level;
        public final long experience;
        public final boolean alive;

        public CharacterSnapshot(String name, long id, int health, int attackPower, int defense, int level, long experience, boolean alive) {
            this.name = name;
            this.id = id;
            this.health = health;
            this.attackPower = attackPower;
            this.defense = defense;
            this.level = level;
            this.experience = experience;
            this.alive = alive;
        }

        @Override
        public String toString() {
            return "CharacterSnapshot{" +
                    "name='" + name + '\'' +
                    ", id=" + id +
                    ", health=" + health +
                    ", attackPower=" + attackPower +
                    ", defense=" + defense +
                    ", level=" + level +
                    ", experience=" + experience +
                    ", alive=" + alive +
                    '}';
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RPGCharacter that = (RPGCharacter) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}
```
