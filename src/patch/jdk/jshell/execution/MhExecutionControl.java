package jdk.jshell.execution;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;

/**
 * Drop-in replacement for {@link LocalExecutionControl} that invokes the
 * snippet's {@code do_it$} method via {@link MethodHandle} instead of
 * {@link Method#invoke}.
 *
 * <p>CheerpJ's WASM reflection bridge silently drops runtime exceptions from
 * {@code Method.invoke} — the {@link InvocationTargetException} is never
 * thrown — so {@code SnippetEvent.exception()} ends up null and user-code
 * failures (a thrown {@code RuntimeException}, an OOB access in a {@code for}
 * body, a {@code 1/0} initializer) are invisible. {@code MethodHandle
 * .invokeWithArguments} uses a separate code path; if CheerpJ implements it
 * correctly, the exception propagates and JShell sees it.
 *
 * <p>We re-wrap the user exception in {@code InvocationTargetException} so
 * the inherited {@code invoke(String, String)} handles it identically to a
 * normal {@code Method.invoke} flow (it unwraps the cause and calls
 * {@code throwConvertedInvocationException}, producing a {@code UserException}
 * that JShell attaches to the {@code SnippetEvent}).
 *
 * <p>We bypass {@link LocalExecutionControl}'s thread-group machinery
 * deliberately: (a) CheerpJ is single-threaded so it adds nothing, (b) the
 * thread's {@code UncaughtExceptionHandler}-based capture has the same
 * {@code Method.invoke} bug. {@code /stop} isn't a concern in-browser.
 */
public class MhExecutionControl extends LocalExecutionControl {

    /** SPI provider, registered programmatically via JShell.Builder. */
    public static class Provider implements ExecutionControlProvider {
        @Override public String name() { return "mh-local"; }
        @Override public ExecutionControl generate(ExecutionEnv env, Map<String,String> p) {
            return new MhExecutionControl();
        }
    }

    @Override
    protected String invoke(Method doitMethod) throws Exception {
        MethodHandle mh = MethodHandles.lookup().unreflect(doitMethod);
        try {
            return valueString(mh.invokeWithArguments());
        } catch (Throwable userException) {
            // MethodHandle throws the user exception directly. Re-wrap so
            // the inherited invoke(String, String) converts it to a
            // UserException the same way it would for Method.invoke.
            throw new InvocationTargetException(userException);
        }
    }
}
