package org.apache.commons.logging;

/** Compile-only logging boundary for isolated download-policy checks. */
public class Log {
    public boolean isDebugEnabled() { return false; }
    public void debug(Object message) { }
    public void error(Object message) { }
    public void warn(Object message, Throwable error) { }
}
