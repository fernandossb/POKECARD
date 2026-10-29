import java.util.List;
import java.util.Map;

/** Runs with dalvikvm on a real Android device to exercise Android ICU, not the host JVM regex engine. */
public final class TrainerCatalogSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> catalog = Class.forName("com.tcgtrainer.engine.TrainerCatalog");
        Map<?, ?> definitions = (Map<?, ?>) catalog.getMethod("getDefinitions").invoke(catalog.getField("INSTANCE").get(null));
        if (definitions.size() < 1000) throw new AssertionError("Incomplete catalog: " + definitions.size());
        Class<?> library = Class.forName("com.tcgtrainer.engine.CardLibrary");
        List<?> entries = (List<?>) library.getMethod("getEntries").invoke(library.getField("INSTANCE").get(null));
        if (entries.isEmpty()) throw new AssertionError("Empty library");
        System.out.println("ANDROID_CATALOG_OK definitions=" + definitions.size() + " entries=" + entries.size());
    }
}
