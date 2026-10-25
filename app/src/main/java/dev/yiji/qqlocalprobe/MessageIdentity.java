package dev.yiji.qqlocalprobe;

/** Inner message identity; transport sequence and message text are not IDs. */
public final class MessageIdentity {
    public static String key(String sender, String uid, String sequence, String clientSequence,
                             String timestamp, String random, String packageIndex, String divisionSequence) {
        String identity = !uid.isEmpty() && !"0".equals(uid) ? "uid:" + uid
                : "seq:" + sequence + ":" + clientSequence + ":" + timestamp + ":" + random;
        return sender + "|" + identity + "|part:" + divisionSequence + ":" + packageIndex;
    }

    private MessageIdentity() { }
}
