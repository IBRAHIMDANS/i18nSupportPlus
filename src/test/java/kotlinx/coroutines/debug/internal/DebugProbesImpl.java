package kotlinx.coroutines.debug.internal;

import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import kotlin.coroutines.Continuation;

/**
 * Compatibility shim for DebugProbesImpl bridging the gap between:
 *  - Kotlin 2.1.x stdlib that calls install$kotlinx_coroutines_core() (added in core:1.9.0)
 *  - The legacy AgentPremain bundled with IU-2024.3.6 (compiled against core:1.8.0-intellij)
 *    that calls getEnableCreationStackTraces(), setEnableCreationStackTraces(), install(), etc.
 *
 * All probe methods are no-ops (debug probing disabled). probeCoroutineCreated returns the
 * original continuation unchanged so coroutines execute normally.
 */
@SuppressWarnings({"unused", "unchecked"})
public final class DebugProbesImpl {

    public static final DebugProbesImpl INSTANCE = new DebugProbesImpl();

    private DebugProbesImpl() {}

    // ---- Kotlin 2.1.x stdlib (CoroutineDumpState) ----

    public void install$kotlinx_coroutines_core() {}

    public void uninstall$kotlinx_coroutines_core() {}

    // ---- Kotlin coroutines probing (called on every coroutine creation/resume/suspend) ----

    /** Must return the continuation unchanged — this is how coroutine creation is tracked. */
    public <T> Continuation<T> probeCoroutineCreated$kotlinx_coroutines_core(Continuation<? super T> completion) {
        return (Continuation<T>) completion;
    }

    public void probeCoroutineResumed$kotlinx_coroutines_core(Continuation<?> frame) {}

    public void probeCoroutineSuspended$kotlinx_coroutines_core(Continuation<?> frame) {}

    // ---- Legacy AgentPremain (IU-2024.3.6 bundled, core 1.8.0-intellij) ----

    public boolean isInstalled$kotlinx_coroutines_core() { return false; }

    /** 1.9.0 variant used by kotlinx-coroutines-debug module */
    public boolean isInstalled$kotlinx_coroutines_debug() { return false; }

    public boolean getSanitizeStackTraces() { return false; }
    public void setSanitizeStackTraces(boolean value) {}

    public boolean getEnableCreationStackTraces() { return false; }
    public void setEnableCreationStackTraces(boolean value) {}

    /** Old install — enables debug probing (no-op here). */
    public void install() {}

    /** Old uninstall (no-op). */
    public void uninstall() {}

    // ---- 1.9.0 API (kotlinx-coroutines-debug and 2025.x IntelliJ may call these) ----

    public boolean getSanitizeStackTraces$kotlinx_coroutines_core() { return false; }
    public void setSanitizeStackTraces$kotlinx_coroutines_core(boolean value) {}

    public boolean getEnableCreationStackTraces$kotlinx_coroutines_core() { return false; }
    public void setEnableCreationStackTraces$kotlinx_coroutines_core(boolean value) {}

    public boolean getIgnoreCoroutinesWithEmptyContext() { return false; }
    public void setIgnoreCoroutinesWithEmptyContext(boolean value) {}

    // ---- Dump methods (called for coroutine dumps in test failures) ----

    public void dumpCoroutines(PrintStream out) {}

    public List<?> dumpCoroutinesInfo() { return Collections.emptyList(); }

    public List<?> dumpDebuggerInfo() { return Collections.emptyList(); }

    public Object[] dumpCoroutinesInfoAsJsonAndReferences() { return new Object[]{"[]", new Object[0]}; }

    public String hierarchyToString(Object job) { return ""; }

    public String hierarchyToString$kotlinx_coroutines_core(Object job) { return ""; }

    public String enhanceStackTraceWithThreadDumpAsJson(Object info) { return "{}"; }

    public List<?> enhanceStackTraceWithThreadDump(Object info, List<?> frames) { return frames; }
}
