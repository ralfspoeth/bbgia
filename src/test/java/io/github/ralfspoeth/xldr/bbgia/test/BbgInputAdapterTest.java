package io.github.ralfspoeth.xldr.bbgia.test;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.util.*;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class BbgInputAdapterTest {

    private static InputAdapterFactory IAF;
    private InputAdapter all;
    private InputAdapter equity;

    @BeforeAll
    static void setUp() {
        IAF = InputAdapterFactory.of(
                new InputSpec("application/x-bloomberg-out", List.of(), List.of(), Map.of())
        ).orElseThrow();
    }

    @BeforeEach
    void init() {
        all = IAF.createInputAdapter(new InputSpec("application/x-bloomberg-out", List.of(
                new RecordSelectorSpec("all", Locator.every(), List.of(
                        new FieldSelectorSpec("runDate", "tag:RUNDATE", DataType.TEMPORAL),
                        new FieldSelectorSpec("lastUpdate", "LAST_UPDATE_DATE_EOD", DataType.TEMPORAL),
                        new FieldSelectorSpec("price", "PX_LAST_EOD", DataType.DECIMAL),
                        new FieldSelectorSpec("vola", "VOLATILITY_162W", DataType.DECIMAL)
                ))
        ), List.of(), Map.of()));
        equity = IAF.createInputAdapter(new InputSpec("application/x-bloomberg-out", List.of(
                new RecordSelectorSpec("equity", new Locator.Where(
                        new Discriminator.Matches(
                                new Selector.Text("#id"), Pattern.compile(".*Equity"))
                ), List.of(
                        new FieldSelectorSpec("runDate", "tag:RUNDATE", DataType.TEMPORAL),
                        new FieldSelectorSpec("lastUpdate", "LAST_UPDATE_DATE_EOD", DataType.TEMPORAL),
                        new FieldSelectorSpec("price", "PX_LAST_EOD", DataType.DECIMAL),
                        new FieldSelectorSpec("vola", "VOLATILITY_162W", DataType.DECIMAL)))
        ), List.of(), Map.of()));
    }


    private static final List<String> FIELDS =
            List.of("#id", "runDate", "price", "lastUpdate", "vola");

    /**
     * Real replies are development samples and are not in the repository - they
     * carry a firm name, a Data License account and the securities it is
     * entitled to. Drop your own into {@code src/test/resources} and the two
     * tests below run over them; on a clean clone they skip.
     * <p>
     * A skipped test is indistinguishable from a broken one, which is why
     * nothing that matters is only checked here. Every claim about what this
     * adapter does is also made against a reply written into these sources -
     * {@link #MIXED} and {@link BbgConformanceTest} - and those always run. What
     * the samples add is breadth: 27 real files, several hundred thousand lines,
     * yellow keys and edge cases nobody would think to type out.
     */
    private static final String SAMPLES_MISSING =
            "no .out samples in src/test/resources; this test needs real replies";

    @Test
    public void listResources() throws IOException {
        var fixtures = fixtures();
        assumeFalse(fixtures.isEmpty(), SAMPLES_MISSING);
        fixtures.forEach(f -> parse(all, "all", f).forEach(System.out::println));
    }

    /**
     * The discriminator picks lines out of the one data section, which is the
     * whole reason a reply file has record selectors at all: one request may ask
     * for equities and bonds together and they land in different tables.
     * <p>
     * Asserted as a relation between the two reads rather than against a count,
     * so it says something whatever the samples happen to hold.
     */
    @Test
    public void theDiscriminatorKeepsOnlyItsOwnLines() throws IOException {
        var fixtures = fixtures();
        assumeFalse(fixtures.isEmpty(), SAMPLES_MISSING);
        for (var fixture : fixtures) {
            var everything = ids(all, "all", fixture);
            var equities = ids(equity, "equity", fixture);
            assertTrue(everything.containsAll(equities), fixture + ": " + equities);
            assertEquals(
                    everything.stream().filter(id -> id.endsWith("Equity")).toList(),
                    equities,
                    fixture + ": the equity selector keeps exactly the lines whose #id says so");
        }
    }

    /**
     * One request, four kinds of security, and the yellow key at the end of the
     * {@code #id} is the only thing telling them apart.
     * <p>
     * The same claim as {@link #theDiscriminatorKeepsOnlyItsOwnLines}, made
     * against a file written here rather than against the corpus. Three of the
     * fixtures do mix Equity with Index or Corp, so that test is not vacuous -
     * but which three is an accident of what was downloaded, the files are not
     * in the repository, and none of them lets a reader see the expected answer
     * beside the input. This one is six lines and the split is on the page.
     */
    private static final String MIXED = """
            START-OF-FILE
            RUNDATE=20260822
            DATEFORMAT=yyyymmdd
            START-OF-FIELDS
            PX_LAST_EOD
            LAST_UPDATE_DATE_EOD
            END-OF-FIELDS
            START-OF-DATA
            MFGEPIC SW Equity|0|2|15.360|20260820|
            T 4 02/15/34 Govt|0|2|99.125|20260820|
            VOD LN Equity|0|2|71.240|20260820|
            EUR Curncy|0|2|1.0842|20260820|
            IBM 3.5 05/15/29 Corp|0|2|101.500|20260820|
            DAI GY Equity|0|2|54.980|20260820|
            END-OF-DATA
            END-OF-FILE
            """;

    @Test
    public void theDiscriminatorKeepsTheEquitiesOutOfAmixedReply() {
        assertEquals(
                List.of("MFGEPIC SW Equity", "T 4 02/15/34 Govt", "VOD LN Equity",
                        "EUR Curncy", "IBM 3.5 05/15/29 Corp", "DAI GY Equity"),
                idsOf(all, "all", MIXED),
                "every line, in the order the file wrote them");

        assertEquals(
                List.of("MFGEPIC SW Equity", "VOD LN Equity", "DAI GY Equity"),
                idsOf(equity, "equity", MIXED),
                "and only the three whose #id ends in Equity");
    }

    /**
     * The pattern is matched in full rather than searched for, which is what
     * {@code matches} means everywhere in a spec: a bond whose issuer happens to
     * be called Equity Residential is not an equity.
     */
    @Test
    public void theDiscriminatorMatchesInFullRatherThanAnywhere() {
        var confusing = MIXED.replace(
                "IBM 3.5 05/15/29 Corp|0|2|101.500|20260820|",
                "Equity Residential 3 01/15/30 Corp|0|2|101.500|20260820|");
        assertEquals(
                List.of("MFGEPIC SW Equity", "VOD LN Equity", "DAI GY Equity"),
                idsOf(equity, "equity", confusing));
    }

    /** the ids one record selector keeps out of a reply written here */
    private List<String> idsOf(InputAdapter adapter, String recordSelector, String content) {
        try (var is = new ByteArrayInputStream(content.getBytes(US_ASCII));
             var rows = adapter.parse(is, recordSelector, new HashSet<>(FIELDS)).rows()) {
            return rows.map(r -> String.valueOf(r.get("#id"))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(recordSelector, e);
        }
    }

    /**
     * A record selector the spec does not declare is a typo in a mapping, and
     * the adapter is the only place it can surface. It used to hand back an
     * empty result, so a spec asking the equity adapter for "all" loaded nothing
     * and said nothing - which is how this was noticed.
     */
    @Test
    public void refusesArecordSelectorTheSpecDoesNotDeclare() throws IOException {
        try (var is = new ByteArrayInputStream(MIXED.getBytes(US_ASCII))) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> equity.parse(is, "all", new HashSet<>(FIELDS)));
            assertTrue(thrown.getMessage().contains("equity"),
                    "and says what is declared: " + thrown.getMessage());
        }
    }

    /** and so is a field the record selector has not got */
    @Test
    public void refusesAfieldSelectorTheRecordSelectorHasNot() throws IOException {
        try (var is = new ByteArrayInputStream(MIXED.getBytes(US_ASCII))) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> all.parse(is, "all", Set.of("noSuchField")));
            assertTrue(thrown.getMessage().contains("noSuchField"), thrown.getMessage());
        }
    }

    /** the {@code #id} of every line one record selector keeps */
    private List<String> ids(InputAdapter adapter, String recordSelector, String fixture) {
        try (var is = getClass().getResourceAsStream("/" + fixture)) {
            assertNotNull(is, fixture);
            try (var rows = adapter.parse(is, recordSelector, new HashSet<>(FIELDS)).rows()) {
                return rows.map(r -> String.valueOf(r.get("#id"))).toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(fixture, e);
        }
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
        var module = BbgInputAdapterTest.class.getModule();
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
    List<String> parse(InputAdapter adapter, String recordSelector, String name) {
        try (var is = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(is, name);
            try (var rows = adapter.parse(is, recordSelector, new HashSet<>(FIELDS)).rows()) {
                return rows.map(r -> format(r, FIELDS)).toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(name, e);
        }
    }

    private String format(Row row, Collection<String> fields) {
        return fields.stream().map(f -> "%s: %s".formatted(f, row.get(f)))
                .collect(joining(", ", "{", "}"));
    }
}