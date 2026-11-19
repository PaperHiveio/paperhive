package org.abdullahcxd.paperhive.host.software.velocity;

import org.abdullahcxd.paperhive.host.software.ClassPatch;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Velocity's main thread ends by waiting for the proxy to shut down, in {@code VelocityServer.awaitProxyShutdown()},
 * with Netty's {@code syncUninterruptibly()}. On its own JVM that thread just dies with the JVM; in a hive nothing
 * ends it, and interrupting it (what Paperhive does with a stopped server's leftover threads) is ignored, so the
 * proxy's classes could never be unloaded. This makes the wait {@code sync()}: the same wait, but an interrupt ends it.
 */
final class VelocityShutdownPatch implements ClassPatch {

    private static final String SERVER = "com/velocitypowered/proxy/VelocityServer";

    @Override
    public boolean appliesTo(@NotNull String className) {
        return className.equals(SERVER);
    }

    @Override
    public @NotNull ClassVisitor patch(@NotNull ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("awaitProxyShutdown")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String methodDesc, boolean itf) {
                        // Future, Promise and ChannelFuture all have sync() with the same descriptor as syncUninterruptibly()
                        super.visitMethodInsn(opcode, owner, method.equals("syncUninterruptibly") ? "sync" : method, methodDesc, itf);
                    }
                };
            }
        };
    }

}
