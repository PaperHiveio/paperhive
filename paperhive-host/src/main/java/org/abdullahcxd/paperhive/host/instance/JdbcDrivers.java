package org.abdullahcxd.paperhive.host.instance;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.sql.Driver;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Deregisters JDBC drivers an instance registered (MySQL and SQLite drivers do so when first loaded), which
 * {@code DriverManager} would otherwise keep, and with them the instance's loader, forever.
 * <p>
 * {@code DriverManager} only shows and removes a driver for code whose class loader can load that driver's class,
 * so the host can't do it directly: a tiny helper class is generated and defined inside the instance loader, and
 * inside every loader in the instance that defined classes (Paper's plugin loaders, server halves' loaders), which is
 * where a plugin's shaded driver lives.
 */
@Slf4j
final class JdbcDrivers {

    static final String HELPER = "paperhive.internal.JdbcCleanup";
    private static final byte[] HELPER_BYTES = helperBytes();

    private JdbcDrivers() {}

    /** @param innerLoaders loaders inside the instance (children of {@code loader}) that may have registered drivers */
    static void deregister(InstanceClassLoader loader, Collection<ClassLoader> innerLoaders) {
        try {
            deregisterVisibleTo(loader.defineHelper(HELPER, HELPER_BYTES), loader);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log.warn("Could not deregister JDBC drivers of instance {}", loader.getInstanceName(), e);
        }
        for (ClassLoader inner : innerLoaders) {
            try {
                Class<?> helper = InstanceHooks.defineHelperIn(inner, HELPER, HELPER_BYTES);
                if (helper != null) deregisterVisibleTo(helper, loader);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                log.warn("Could not deregister JDBC drivers of {} in instance {}", inner, loader.getInstanceName(), e);
            }
        }
    }

    /** Deregisters the instance's drivers that {@code helper}'s loader can see (its own and its parents'). */
    private static void deregisterVisibleTo(@NotNull Class<?> helper, InstanceClassLoader loader) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        List<Driver> drivers = Collections.list((Enumeration<Driver>) helper.getMethod("drivers").invoke(null));
        for (Driver driver : drivers) {
            if (!ServerInstance.ownedBy(driver.getClass().getClassLoader(), loader)) continue;
            helper.getMethod("deregister", Driver.class).invoke(null, driver);
            log.debug("Deregistered JDBC driver {} of instance {}", driver.getClass().getName(), loader.getInstanceName());
        }
    }

    /**
     * <pre>
     * public final class paperhive.internal.JdbcCleanup {
     *     public static Enumeration drivers() { return DriverManager.getDrivers(); }
     *     public static void deregister(Driver d) throws SQLException { DriverManager.deregisterDriver(d); }
     * }
     * </pre>
     */
    private static byte[] helperBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER.replace('.', '/'), null, "java/lang/Object", null);

        MethodVisitor drivers = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "drivers", "()Ljava/util/Enumeration;", null, null);
        drivers.visitCode();
        drivers.visitMethodInsn(Opcodes.INVOKESTATIC, "java/sql/DriverManager", "getDrivers", "()Ljava/util/Enumeration;", false);
        drivers.visitInsn(Opcodes.ARETURN);
        drivers.visitMaxs(0, 0);
        drivers.visitEnd();

        MethodVisitor deregister = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "deregister", "(Ljava/sql/Driver;)V",
                null, new String[]{"java/sql/SQLException"});
        deregister.visitCode();
        deregister.visitVarInsn(Opcodes.ALOAD, 0);
        deregister.visitMethodInsn(Opcodes.INVOKESTATIC, "java/sql/DriverManager", "deregisterDriver", "(Ljava/sql/Driver;)V", false);
        deregister.visitInsn(Opcodes.RETURN);
        deregister.visitMaxs(0, 0);
        deregister.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

}
