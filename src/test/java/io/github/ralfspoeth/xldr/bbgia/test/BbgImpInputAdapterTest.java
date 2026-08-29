package io.github.ralfspoeth.xldr.bbgia.test;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BbgImpInputAdapterTest {

    private static InputAdapterFactory IAF;
    private InputAdapter ia;

    @BeforeAll
    static void setUp() {
        IAF = InputAdapterFactory.of(
                new InputSpec("text/plain", List.of(), List.of(), Map.of())
        ).orElseThrow();
    }

    @BeforeEach
    void init() {
        ia = IAF.createInputAdapter(new InputSpec("text/plain", List.of(
                new RecordSelectorSpec("all", Locator.every(), List.of(
                        new FieldSelectorSpec("runDate", "tag:RUNDATE", DataType.TEMPORAL),
                        new FieldSelectorSpec("lastUpdate", "LAST_UPDATE_DATE_EOD", DataType.TEMPORAL),
                        new FieldSelectorSpec("price", "PX_LAST_EOD", DataType.DECIMAL),
                        new FieldSelectorSpec("vola", "VOLATILITY_162W", DataType.DECIMAL)
                ))
        ), List.of(), Map.of()));
    }

    @Test
    public void listResources() throws IOException {
        var fixtures = fixtures();
        // a listing that quietly finds nothing would let this pass while reading
        // no file at all, which is the one way a test like this fails silently
        assertFalse(fixtures.isEmpty(), "no .out fixtures in " + getClass().getModule());
        fixtures.forEach(f -> parse(f, List.of("#id", "runDate", "price", "lastUpdate", "vola")));
    }

    /**
     * Every {@code .out} the test module carries.
     * <p>
     * Not through the class loader, and not as a directory: this class is in a
     * named module, and a named module's resources are not on any classpath the
     * loader searches, so {@code getClassLoader().getResource} finds nothing at
     * all. {@link Class#getResourceAsStream} does reach them - see
     * {@link #parse} - but it hands back one resource by name and there is no
     * URL for a directory to walk, which is what {@code Files.list} needed.
     * <p>
     * {@link ModuleReader} is the API that enumerates them. It reads the module
     * as the module system itself does, so this works the same whether the
     * module is an exploded {@code target/test-classes} or a packaged jar, and
     * needs nothing of Maven's layout.
     */
    private static List<String> fixtures() throws IOException {
        var module = BbgImpInputAdapterTest.class.getModule();
        var reference = ModuleLayer.boot()
                .configuration()
                .findModule(module.getName())
                .orElseThrow()
                .reference();
        // the stream is only valid while the reader is open, so it is drained here
        try (ModuleReader reader = reference.open()) {
            return reader.list()
                    .filter(name -> name.endsWith(".out"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * A leading slash is right here and was wrong on the class loader: to
     * {@link Class#getResourceAsStream} it means an absolute name, which is
     * asked of the module. A resource at the root sits in no package, so no
     * {@code opens} is needed to read it - though this module is open anyway.
     */
    void parse(String name, List<String> fields) {
        try (var is = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(is, name);
            ia.parse(is, "all", new HashSet<>(fields))
                    .rows()
                    .map(r -> format(r, fields))
                    .forEach(System.out::println);
        } catch (IOException e) {
            throw new UncheckedIOException(name, e);
        }
    }

    private String format(Row row, Collection<String> fields) {
        return fields.stream().map(f -> "%s: %s".formatted(f, row.get(f)))
                .collect(joining(", ", "{", "}"));
    }
}