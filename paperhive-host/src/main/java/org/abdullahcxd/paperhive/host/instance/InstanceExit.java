package org.abdullahcxd.paperhive.host.instance;

/**
 * Thrown in place of a real JVM exit to unwind the instance thread that called {@code System.exit}
 * (or {@code Runtime.exit/halt}). An {@link Error} so ordinary {@code catch (Exception)} blocks let it through.
 */
public final class InstanceExit extends Error {

    private final int status;

    InstanceExit(int status) {
        super("Instance exit with status " + status, null, false, false);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

}
