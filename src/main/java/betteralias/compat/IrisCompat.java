package betteralias.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Soft dependency on Iris, done through reflection so the mod builds and runs without Iris on the classpath.
 *
 * <p>Uses Iris' stable public API: {@code net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()}.
 * The lookup happens once; after that each call is a cheap MethodHandle invocation (this is called every frame).
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("better-alias");
    private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

    private static MethodHandle isShaderPackInUse;
    private static boolean lookupFailed;

    private IrisCompat() {
    }

    public static boolean isIrisLoaded() {
        return IRIS_LOADED;
    }

    public static boolean isShaderPackInUse() {
        if (!IRIS_LOADED || lookupFailed) {
            return false;
        }

        try {
            if (isShaderPackInUse == null) {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi", true, IrisCompat.class.getClassLoader());
                Object api = apiClass.getMethod("getInstance").invoke(null);
                isShaderPackInUse = MethodHandles.publicLookup()
                        .findVirtual(apiClass, "isShaderPackInUse", MethodType.methodType(boolean.class))
                        .bindTo(api);
            }
            return (boolean) isShaderPackInUse.invokeExact();
        } catch (Throwable t) {
            lookupFailed = true;
            LOGGER.warn("Iris is installed but its API could not be queried; anti-aliasing will stay on with shader packs", t);
            return false;
        }
    }
}
