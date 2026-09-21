package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class GooskerAsyncPathCommitter {
    private static final Set<InFlightKey> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private GooskerAsyncPathCommitter() {
    }

    public static CompletableFuture<?> submitOnce(Supplier<?> supplier, Object object2, String string) {
        InFlightKey inFlightKey = new InFlightKey(object2, string);
        if (!IN_FLIGHT.add(inFlightKey)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Class<?> clazz = Class.forName("net.oxcodsnet.roadarchitect.util.AsyncExecutor");
            Method method = clazz.getMethod("submit", Supplier.class);
            CompletableFuture completableFuture = (CompletableFuture)method.invoke(null, supplier);
            completableFuture.whenComplete((object, throwable) -> {
                if (throwable != null) {
                    IN_FLIGHT.remove(inFlightKey);
                }
            });
            return completableFuture;
        }
        catch (Throwable throwable2) {
            IN_FLIGHT.remove(inFlightKey);
            CompletableFuture completableFuture = new CompletableFuture();
            completableFuture.completeExceptionally(throwable2);
            return completableFuture;
        }
    }

    public static void scheduleCommit(Object object, Object object2, Object object3, List<?> list) {
        ArrayList<CompletableFuture<?>> arrayList = new ArrayList<>(list.size());
        for (Object obj : list) {
            if (!(obj instanceof CompletableFuture)) continue;
            CompletableFuture<?> completableFuture = (CompletableFuture<?>)obj;
            arrayList.add(completableFuture);
        }
        CompletableFuture[] completableFutureArray = arrayList.toArray(new CompletableFuture[0]);
        CompletableFuture.allOf(completableFutureArray).whenComplete((void_, throwable) -> {
            Runnable runnable = () -> GooskerAsyncPathCommitter.commitBatch(object, object2, object3, arrayList);
            try {
                Object object4 = object.getClass().getMethod("method_8503", new Class[0]).invoke(object, new Object[0]);
                if (object4 instanceof Executor) {
                    Executor executor = (Executor)object4;
                    executor.execute(runnable);
                    return;
                }
                Method method = object4.getClass().getMethod("execute", Runnable.class);
                method.invoke(object4, runnable);
            }
            catch (Throwable throwable2) {
                GooskerAsyncPathCommitter.releaseCompletedKeys(object, arrayList);
                throwable2.printStackTrace();
            }
        });
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Loose catch block
     */
    private static void commitBatch(Object object, Object object2, Object object3, List<CompletableFuture<?>> list) {
        try {
            Object object4 = object2.getClass().getMethod("edges", new Class[0]).invoke(object2, new Object[0]);
            Method method = GooskerAsyncPathCommitter.findMethod(object3.getClass(), "putPath", 4);
            Method method2 = GooskerAsyncPathCommitter.findMethod(object4.getClass(), "setStatus", 2);
            Class<?> clazz = method.getParameterTypes()[3];
            Object obj = Enum.valueOf(clazz.asSubclass(Enum.class), "PENDING");
            Object obj2 = Enum.valueOf(clazz.asSubclass(Enum.class), "FAILED");
            Class<?> clazz2 = method2.getParameterTypes()[1];
            Object obj3 = Enum.valueOf(clazz2.asSubclass(Enum.class), "SUCCESS");
            Object obj4 = Enum.valueOf(clazz2.asSubclass(Enum.class), "FAILURE");
            for (CompletableFuture<?> completableFuture : list) {
                String string = null;
                try {
                    Object object5 = completableFuture.getNow(null);
                    if (object5 == null) {
                        continue;
                    }
                    string = (String)GooskerAsyncPathCommitter.invokeDeclared(object5, "edgeId");
                    String string2 = (String)GooskerAsyncPathCommitter.invokeDeclared(object5, "from");
                    String string3 = (String)GooskerAsyncPathCommitter.invokeDeclared(object5, "to");
                    List list2 = (List)GooskerAsyncPathCommitter.invokeDeclared(object5, "path");
                    boolean bl = list2 != null && !list2.isEmpty();
                    method.invoke(object3, string2, string3, list2, bl ? obj : obj2);
                    method2.invoke(object4, string, bl ? obj3 : obj4);
                }
                catch (CompletionException completionException) {
                    // Failed computations have no result object from which to recover an edge id.
                }
                catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
                finally {
                    if (string != null) {
                        IN_FLIGHT.remove(new InFlightKey(object, string));
                    }
                }
            }
            GooskerAsyncPathCommitter.invokeIfPresent(object3, "method_80");
            GooskerAsyncPathCommitter.invokeIfPresent(object2, "method_80");
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        finally {
            GooskerAsyncPathCommitter.releaseCompletedKeys(object, list);
        }
    }

    private static void releaseCompletedKeys(Object object, List<CompletableFuture<?>> list) {
        for (CompletableFuture<?> completableFuture : list) {
            if (!completableFuture.isDone() || completableFuture.isCompletedExceptionally()) continue;
            try {
                Object var4_4 = completableFuture.getNow(null);
                if (var4_4 == null) continue;
                String string = (String)GooskerAsyncPathCommitter.invokeDeclared(var4_4, "edgeId");
                IN_FLIGHT.remove(new InFlightKey(object, string));
            }
            catch (Throwable throwable) {}
        }
    }

    private static Object invokeDeclared(Object object, String string) throws Exception {
        Method method = object.getClass().getDeclaredMethod(string, new Class[0]);
        method.setAccessible(true);
        return method.invoke(object, new Object[0]);
    }

    private static Method findMethod(Class<?> clazz, String string, int n) throws NoSuchMethodException {
        for (Class<?> clazz2 = clazz; clazz2 != null; clazz2 = clazz2.getSuperclass()) {
            for (Method method : clazz2.getDeclaredMethods()) {
                if (!method.getName().equals(string) || method.getParameterCount() != n) continue;
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + string + "/" + n);
    }

    private static void invokeIfPresent(Object object, String string) {
        try {
            object.getClass().getMethod(string, new Class[0]).invoke(object, new Object[0]);
        }
        catch (NoSuchMethodException noSuchMethodException) {
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private static final class InFlightKey {
        private final Object world;
        private final String edgeId;
        private final int hash;

        private InFlightKey(Object object, String string) {
            this.world = object;
            this.edgeId = string;
            this.hash = 31 * System.identityHashCode(object) + string.hashCode();
        }

        public int hashCode() {
            return this.hash;
        }

        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof InFlightKey)) {
                return false;
            }
            InFlightKey inFlightKey = (InFlightKey)object;
            return this.world == inFlightKey.world && this.edgeId.equals(inFlightKey.edgeId);
        }
    }
}
