package json.processor;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exercises {@link JsonRecordProcessor} by actually invoking {@code javac}
 * in-process against small generated source files, the way a real build
 * would, rather than unit-testing the processor's internals in isolation —
 * an annotation processor's only real contract is "what does it do to a
 * compilation." No compile-testing library: this project has none, so the
 * harness below drives {@link ToolProvider#getSystemJavaCompiler()}
 * directly. {@code java json.processor.JsonRecordProcessorTest} exits
 * non-zero on failure, same convention as the other test runners here.
 *
 * <p>Each scenario's source embeds its own {@code Runner.run()} that builds
 * a sample value, round-trips it through the generated codec, and returns a
 * plain {@code String} verdict — keeping the reflection surface this
 * harness itself needs down to "load one class, call one no-arg static
 * method," regardless of how many record components or nested types a
 * scenario has.
 */
public final class JsonRecordProcessorTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        simpleRecordRoundTrips();
        customJsonNameProducesWireName();
        nestedRecordRoundTrips();
        listOfPrimitivesRoundTrips();
        nullableFieldsRoundTripPresentAndAbsent();
        enumFieldRoundTrips();
        composedListOfNestedRecordsRoundTrips();
        unsupportedFieldTypeFailsCompilationClearly();

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ scenarios

    private static void simpleRecordRoundTrips() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;

            @JsonRecord
            record Point(int x, int y) {}

            final class Runner {
                public static String run() {
                    Point p = new Point(3, 4);
                    byte[] bytes = json.Json.toBytes(PointCodec.CODEC, p);
                    Point back = json.Json.parse(PointCodec.CODEC, bytes);
                    if (!p.equals(back)) return "MISMATCH:" + back;
                    return "OK:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"x\":3,\"y\":4}", result, "simple record round trip + exact wire shape");
    }

    private static void customJsonNameProducesWireName() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;
            import json.JsonName;

            @JsonRecord
            record TaxResponse(@JsonName("tax_owed") double taxOwed,
                                       @JsonName("effective_rate") double effectiveRate) {}

            final class Runner {
                public static String run() {
                    TaxResponse r = new TaxResponse(11158.5, 0.131);
                    byte[] bytes = json.Json.toBytes(TaxResponseCodec.CODEC, r);
                    TaxResponse back = json.Json.parse(TaxResponseCodec.CODEC, bytes);
                    if (!r.equals(back)) return "MISMATCH:" + back;
                    return "OK:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"tax_owed\":11158.5,\"effective_rate\":0.131}", result, "@JsonName overrides the wire field name");
    }

    private static void nestedRecordRoundTrips() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;

            @JsonRecord
            record Address(String city, int zip) {}

            @JsonRecord
            record Person(String name, Address address) {}

            final class Runner {
                public static String run() {
                    Person p = new Person("Ada", new Address("London", 90210));
                    byte[] bytes = json.Json.toBytes(PersonCodec.CODEC, p);
                    Person back = json.Json.parse(PersonCodec.CODEC, bytes);
                    if (!p.equals(back)) return "MISMATCH:" + back;
                    return "OK:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"name\":\"Ada\",\"address\":{\"city\":\"London\",\"zip\":90210}}", result, "nested @JsonRecord composes correctly");
    }

    private static void listOfPrimitivesRoundTrips() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;
            import java.util.List;

            @JsonRecord
            record Tags(List<String> values) {}

            final class Runner {
                public static String run() {
                    Tags t = new Tags(List.of("a", "b", "c"));
                    byte[] bytes = json.Json.toBytes(TagsCodec.CODEC, t);
                    Tags back = json.Json.parse(TagsCodec.CODEC, bytes);
                    if (!t.equals(back)) return "MISMATCH:" + back;
                    return "OK:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"values\":[\"a\",\"b\",\"c\"]}", result, "List<String> component round trips via Codecs.listOf");
    }

    private static void nullableFieldsRoundTripPresentAndAbsent() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;

            @JsonRecord
            record Validation(boolean valid, String reason) {}

            final class Runner {
                public static String run() {
                    Validation present = new Validation(false, "bad input");
                    Validation absent = new Validation(true, null);

                    byte[] b1 = json.Json.toBytes(ValidationCodec.CODEC, present);
                    Validation back1 = json.Json.parse(ValidationCodec.CODEC, b1);
                    if (!present.equals(back1)) return "MISMATCH_PRESENT:" + back1;

                    byte[] b2 = json.Json.toBytes(ValidationCodec.CODEC, absent);
                    Validation back2 = json.Json.parse(ValidationCodec.CODEC, b2);
                    if (!absent.equals(back2)) return "MISMATCH_ABSENT:" + back2;

                    return "OK:" + new String(b1, java.nio.charset.StandardCharsets.UTF_8)
                         + "|" + new String(b2, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"valid\":false,\"reason\":\"bad input\"}|{\"valid\":true,\"reason\":null}", result,
                "nullable reference-typed component handles both present and null");
    }

    private static void enumFieldRoundTrips() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;

            @JsonRecord
            record Account(String name, Status status) {
                enum Status { ACTIVE, SUSPENDED }
            }

            final class Runner {
                public static String run() {
                    Account a = new Account("bob", Account.Status.SUSPENDED);
                    byte[] bytes = json.Json.toBytes(AccountCodec.CODEC, a);
                    Account back = json.Json.parse(AccountCodec.CODEC, bytes);
                    if (!a.equals(back)) return "MISMATCH:" + back;
                    return "OK:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"name\":\"bob\",\"status\":\"SUSPENDED\"}", result, "enum component encodes as its name() via Codecs.enumOf");
    }

    private static void composedListOfNestedRecordsRoundTrips() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;
            import java.util.List;

            @JsonRecord
            record Item(String sku, int qty) {}

            @JsonRecord
            record Order(List<Item> items) {}

            final class Runner {
                public static String run() {
                    Order o = new Order(List.of(new Item("A1", 2), new Item("B2", 5)));
                    byte[] bytes = json.Json.toBytes(OrderCodec.CODEC, o);
                    Order back = json.Json.parse(OrderCodec.CODEC, bytes);
                    if (!o.equals(back)) return "MISMATCH:" + back;
                    return "OK:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            """;
        String result = compileAndRun(source);
        eq("OK:{\"items\":[{\"sku\":\"A1\",\"qty\":2},{\"sku\":\"B2\",\"qty\":5}]}", result,
                "List<T> of a nested @JsonRecord type composes listOf() with the nested type's generated codec");
    }

    private static void unsupportedFieldTypeFailsCompilationClearly() throws Exception {
        String source = """
            package t;
            import json.JsonRecord;
            import java.util.Map;

            @JsonRecord
            record Bad(Map<String, String> data) {}
            """;
        checks++;
        try {
            compile(source);
            failures++;
            System.out.println("FAIL unsupportedFieldTypeFailsCompilationClearly: expected compilation to fail, it succeeded");
        } catch (CompilationFailedException expected) {
            if (!expected.getMessage().contains("Map isn't auto-supported")) {
                failures++;
                System.out.println("FAIL unsupportedFieldTypeFailsCompilationClearly: wrong error: " + expected.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ harness

    private static String compileAndRun(String source) throws Exception {
        Path outDir = compile(source);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {outDir.toUri().toURL()}, JsonRecordProcessorTest.class.getClassLoader())) {
            Class<?> runner = loader.loadClass("t.Runner");
            Method run = runner.getMethod("run");
            run.setAccessible(true); // Runner is package-private (only one public type per file is allowed)
            return (String) run.invoke(null);
        }
    }

    /**
     * {@code System.getProperty("java.class.path")} isn't reliable here:
     * under some launchers (observed with Maven's exec-maven-plugin forking
     * a JVM) it doesn't reflect the effective classpath the JVM actually
     * resolved classes from. Asking each class we actually need where it
     * was loaded from is robust regardless of how the surrounding process
     * assembled its own classpath.
     */
    private static String requiredClasspath() {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        for (Class<?> c : new Class<?>[] {
                json.Json.class, json.JsonCodec.class, json.Codecs.class,
                JsonRecordProcessor.class, JsonRecordProcessorTest.class,
        }) {
            parts.add(locationOf(c));
        }
        return String.join(File.pathSeparator, parts);
    }

    private static String locationOf(Class<?> c) {
        try {
            return java.nio.file.Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new RuntimeException("could not determine classpath location of " + c, e);
        }
    }

    private static Path compile(String source) throws Exception {
        Path dir = Files.createTempDirectory("json-record-processor-test");
        Path srcFile = dir.resolve("src/t/Test.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, source, StandardCharsets.UTF_8);
        Path outDir = dir.resolve("out");
        Files.createDirectories(outDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringBuilder diagnostics = new StringBuilder();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outDir.toFile()));
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(List.of(srcFile.toFile()));
            List<String> options = List.of("-classpath", requiredClasspath(), "-proc:full");
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, d -> diagnostics.append(d).append('\n'), options, null, units);
            task.setProcessors(List.of(new JsonRecordProcessor()));
            boolean ok = task.call();
            if (!ok) {
                throw new CompilationFailedException(diagnostics.toString());
            }
        }
        return outDir;
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual == null : expected.equals(actual)) return;
        failures++;
        System.out.println("FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    @SuppressWarnings("serial") // control flow within this test harness, never actually serialized
    private static final class CompilationFailedException extends RuntimeException {
        CompilationFailedException(String message) {
            super(message);
        }
    }
}
