@ResolvingVendingMachine.ThreadSafe
class ResolvingVendingMachine {
    @interface ThreadSafe {}

    private final int[] prices;
    private final Dispenser dispenser;
    private final Object lock = new Object();
    private long funds = 0;
    private int[] stock;
    private final java.util.Map<Long, Reservation> reservations = new java.util.HashMap<>();

    public ResolvingVendingMachine(int[] prices, Dispenser dispenser) {
        this.prices = prices;
        this.dispenser = dispenser;
        stock = new int[prices.length];
    }

    public void restock(int product, int quantity) {
        synchronized (lock) {
            if (product < 0 || product >= prices.length || quantity < 0) {
                throw new IllegalArgumentException("Invalid restock parameters");
            }
            stock[product] += quantity;
        }
    }

    public boolean insertFunds(long amount) {
        synchronized (lock) {
            if (amount < 0) {
                throw new IllegalArgumentException("Invalid funds amount");
            }
            funds += amount;
        }
        return true;
    }

    public boolean purchaseBundle(long transactionId, int[] quantities) {
        synchronized (lock) {
            if (transactionId <= 0 || reservations.containsKey(transactionId)) {
                throw new IllegalArgumentException("Invalid transaction ID");
            }

            Reservation reservation = new Reservation(transactionId, quantities);
            if (!validateReservation(reservation)) {
                return false;
            }

            reservations.put(transactionId, reservation);
        }

        try {
            boolean result = dispenser.dispense(reservation.transactionId, reservation.quantities);
            if (result) {
                synchronized (lock) {
                    commitReservation(reservation);
                }
            } else {
                synchronized (lock) {
                    cancel(transactionId, true);
                }
            }
        } catch (RuntimeException e) {
            synchronized (lock) {
                cancel(transactionId, true);
            }
            throw e;
        }

        return result;
    }

    public boolean cancel(long transactionId) {
        synchronized (lock) {
            if (!reservations.containsKey(transactionId)) {
                return false;
            }
            reservations.remove(transactionId);
        }
        cancel(transactionId, true);
        return true;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            int[] availableStock = stock.clone();
            return new Snapshot(availableStock, funds, reservations);
        }
    }

    private boolean validateReservation(Reservation reservation) {
        for (int i = 0; i < reservation.quantities.length; i++) {
            if (reservation.quantities[i] > 0 && stock[i] < reservation.quantities[i]) {
                return false;
            }
        }

        long totalCost = 0;
        for (int i = 0; i < reservation.quantities.length; i++) {
            totalCost += (long) reservation.quantities[i] * prices[i];
        }

        if (totalCost > funds) {
            return false;
        }

        return true;
    }

    private void commitReservation(Reservation reservation) {
        for (int i = 0; i < reservation.quantities.length; i++) {
            stock[i] -= reservation.quantities[i];
            funds -= (long) reservation.quantities[i] * prices[i];
        }
    }

    private void cancel(long transactionId, boolean rollBackFunds) {
        Reservation reservation = reservations.get(transactionId);
        for (int i = 0; i < reservation.quantities.length; i++) {
            stock[i] += reservation.quantities[i];
        }

        if (rollBackFunds) {
            funds += calculateTotalCost(reservation.quantities);
        }

        reservations.remove(transactionId);
    }

    private long calculateTotalCost(int[] quantities) {
        long totalCost = 0;
        for (int i = 0; i < quantities.length; i++) {
            totalCost += (long) quantities[i] * prices[i];
        }
        return totalCost;
    }

    private static class Reservation {
        final long transactionId;
        final int[] quantities;

        public Reservation(long transactionId, int[] quantities) {
            this.transactionId = transactionId;
            this.quantities = quantities.clone();
        }
    }

    static class Snapshot {
        final int[] availableStock;
        final long availableFunds;
        final java.util.Map<Long, Reservation> transactionStatuses;

        public Snapshot(int[] availableStock, long availableFunds, java.util.Map<Long, Reservation> transactionStatuses) {
            this.availableStock = availableStock;
            this.availableFunds = availableFunds;
            this.transactionStatuses = transactionStatuses;
        }
    }

    static interface Dispenser {
        boolean dispense(long transactionId, int[] quantities) throws RuntimeException;
    }
}
