package dev.epochex.trading;

public record CommandRequest(long uid, String key, String kind, String side, String orderType,
                             long price, long size, long targetOrderId) {
    public static CommandRequest place(long uid, String key, String side, String type, long price, long size) {
        return new CommandRequest(uid, key, "PLACE", side, type, price, size, 0);
    }
    public static CommandRequest cancel(long uid, String key, long orderId) {
        return new CommandRequest(uid, key, "CANCEL", "", "", 0, 0, orderId);
    }
    void validate() {
        if (uid != 1 && uid != 2) throw new IllegalArgumentException("Demo users are 1 and 2");
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Key must have 1..128 characters");
        if ("PLACE".equals(kind)) {
            if (!("ASK".equals(side) || "BID".equals(side))) throw new IllegalArgumentException("Side must be ASK or BID");
            if (!("GTC".equals(orderType) || "IOC".equals(orderType))) throw new IllegalArgumentException("Type must be GTC or IOC");
            if (price <= 0 || size <= 0 || price > 1_000_000 || size > 1_000_000 || targetOrderId != 0)
                throw new IllegalArgumentException("Invalid integer price, size or target");
        } else if ("CANCEL".equals(kind)) {
            if (targetOrderId <= 0 || price != 0 || size != 0 || !"".equals(side) || !"".equals(orderType))
                throw new IllegalArgumentException("Invalid cancellation");
        } else throw new IllegalArgumentException("Unsupported command");
    }
}
