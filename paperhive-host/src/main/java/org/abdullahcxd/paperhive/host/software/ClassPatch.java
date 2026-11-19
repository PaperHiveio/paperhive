package org.abdullahcxd.paperhive.host.software;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassVisitor;

/**
 * A bytecode patch for one server software's own classes, on top of the JDK-level rewrites every server gets
 * ({@code System.exit}, files, standard streams, ...; see {@code InstanceTransformer}). A {@link ServerSoftware}
 * lists its patches in {@link ServerSoftware#patches()}; they run on every class its servers define, including the
 * classes of its plugins.
 * <pre>{@code
 * final class NoUpdateCheck implements ClassPatch {
 *     public boolean appliesTo(String className) {
 *         return className.equals("com/example/server/UpdateChecker");
 *     }
 *
 *     public ClassVisitor patch(ClassVisitor next) {
 *         return new ClassVisitor(Opcodes.ASM9, next) {
 *             // rewrite what's needed, pass everything else on to next
 *         };
 *     }
 * }
 * }</pre>
 * Keep the stack shape and frames valid: classes are written with computed maxima, but frames aren't recomputed.
 */
public interface ClassPatch {

    /** Whether this patch rewrites the class with this internal name ({@code com/example/Thing}). */
    boolean appliesTo(@NotNull String className);

    /** A visitor that rewrites what this patch is for and passes everything else on to {@code next}. */
    @NotNull ClassVisitor patch(@NotNull ClassVisitor next);

}
