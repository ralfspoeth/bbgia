package io.github.ralfspoeth.xldr.bbgia.test;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * This adapter against the published conformance kit.
 * <p>
 * Since xldr 0.51 the kit checks all ten of the obligations in {@code ia}'s
 * package documentation, and extending this is most of running them. Seven need
 * nothing but a factory, a MIME type, a spec and a sample; three take evidence
 * only this module can produce, through {@link #refusals()} - which is abstract,
 * so the question cannot be left unanswered - and {@code absences()} and
 * {@code breakages()}, which default to empty and skip while saying so.
 * <p>
 * It is worth more here than for an adapter inside the xldr reactor: this one
 * lives in its own repository, against a released version of the SPI. The
 * swift-mt adapter was written the same way and diverged on the typing
 * obligation, because at the time nothing stated it.
 * <p>
 * What stays in {@link BbgInputAdapterTest} is what is wrong with a <em>file</em>
 * rather than with a spec - chiefly a {@code DATEFORMAT} spelling nobody can
 * translate, which the reply declares and no spec could have been checked for.
 * <p>
 * All ten obligations are checked here as of xldr 0.53: nothing is declined. The
 * two that were skipping until then were skipping honestly - this adapter had
 * supplied no sample with a value missing, and did not name the record when a
 * value would not convert, the second being a real gap that the skip is what
 * found.
 */
public class BbgConformanceTest extends InputAdapterContract {

    private static final String MIME_TYPE = "application/x-bloomberg-out";

    /**
     * Two securities, one of which Bloomberg has no price for.
     * <p>
     * The {@code N.A.} matters: it makes one value null while the other stays a
     * {@code BigDecimal}, so the typing obligations are asked about both an
     * absent value and a present one. A sample where every value is filled in
     * would pass them without having been asked anything.
     */
    private static final String SAMPLE = """
            START-OF-FILE
            RUNDATE=20260822
            DATEFORMAT=yyyymmdd
            PROGRAMNAME=getdata
            START-OF-FIELDS
            PX_LAST_EOD
            LAST_UPDATE_DATE_EOD
            END-OF-FIELDS
            TIMESTARTED=Sat Aug 22 05:27:19 BST 2026
            START-OF-DATA
            MFGEPIC SW Equity|0|2|15.360|20260820|
            VOD LN Equity|0|2|N.A.|20260820|
            END-OF-DATA
            TIMEFINISHED=Sat Aug 22 05:27:23 BST 2026
            END-OF-FILE
            """;

    /**
     * Through {@link InputAdapterFactory#of} rather than a constructor: this
     * module does not export the package its factory sits in - none of the
     * adapters do, the class being public only because {@code provides ... with}
     * requires it - so from a test module the service lookup is the interface.
     */
    @Override
    protected @NonNull InputAdapterFactory factory() {
        return InputAdapterFactory.of(new InputSpec(MIME_TYPE, List.of(), List.of(), Map.of()))
                .orElseThrow(() -> new IllegalStateException(
                        "no factory reads " + MIME_TYPE + "; is the bbgia module in the graph?"));
    }

    @Override
    protected @NonNull String mimeType() {
        return MIME_TYPE;
    }

    /**
     * One value of each addressing this format has - a fixed leading value, a
     * header tag, and two fields by name - and four of the five types, so that
     * the typing obligations bite rather than passing on a spec that is all
     * text.
     */
    @Override
    protected @NonNull InputSpec spec() {
        return new InputSpec(MIME_TYPE, List.of(
                new RecordSelectorSpec("all", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "#id", DataType.TEXT),
                        new FieldSelectorSpec("retCode", "#retCode", DataType.INTEGRAL),
                        new FieldSelectorSpec("runDate", "tag:RUNDATE", DataType.TEMPORAL),
                        new FieldSelectorSpec("price", "PX_LAST_EOD", DataType.DECIMAL),
                        new FieldSelectorSpec("asOf", "LAST_UPDATE_DATE_EOD", DataType.TEMPORAL)
                ))
        ), List.of(), Map.of());
    }

    @Override
    protected byte @NonNull [] sample() {
        return SAMPLE.getBytes(US_ASCII);
    }

    /**
     * A short list, and the shortness is the honest answer.
     * <p>
     * Most of what can be wrong with a spec here is wrong about a <em>file</em>
     * rather than about the spec: a {@code DATEFORMAT} spelling this adapter
     * cannot translate is refused when the header is read, because it is the
     * reply that declares it and no spec could have been checked for it ahead of
     * time. That belongs in {@link BbgInputAdapterTest}, not here, and the
     * distinction is what this hook is for - obligation 1 is about the last
     * moment before a file exists.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        var price = new FieldSelectorSpec("price", "PX_LAST_EOD", DataType.DECIMAL);
        return List.of(
                new Refusal("a locator pointing at records, where a reply has one data section"
                        + " and nothing to point at",
                        spec(Map.of(), Locator.at("/data"), price)),
                new Refusal("two record selectors of one name",
                        new InputSpec(MIME_TYPE,
                                List.of(records(Locator.every(), price), records(Locator.every(), price)),
                                List.of(), Map.of())),
                new Refusal("a charset this JVM does not have, which would otherwise surface as a"
                        + " decoding failure on the first reply rather than when the feed is set up",
                        spec(Map.of("charset", "utf-97"), Locator.every(), price)));
    }

    /**
     * A reply Bloomberg has no price in at all.
     * <p>
     * {@code N.A.} is how it says "not available", and this adapter reads it as
     * an absent value rather than as the text - which is the whole of obligation
     * 6 for this format. The main sample already carries one of these beside a
     * priced security; the check wants the field absent in <em>every</em> record,
     * so this one drops the priced line.
     */
    private static final String NOTHING_PRICED = """
            START-OF-FILE
            RUNDATE=20260822
            DATEFORMAT=yyyymmdd
            PROGRAMNAME=getdata
            START-OF-FIELDS
            PX_LAST_EOD
            LAST_UPDATE_DATE_EOD
            END-OF-FIELDS
            TIMESTARTED=Sat Aug 22 05:27:19 BST 2026
            START-OF-DATA
            VOD LN Equity|0|2|N.A.|20260820|
            BARC LN Equity|0|2|N.S.|20260820|
            END-OF-DATA
            TIMEFINISHED=Sat Aug 22 05:27:23 BST 2026
            END-OF-FILE
            """;

    /**
     * A price that is not a number, which is what a reply looks like when a field
     * was requested for a security whose data does not fit it.
     */
    private static final String PRICE_THAT_IS_NOT_A_NUMBER = """
            START-OF-FILE
            RUNDATE=20260822
            DATEFORMAT=yyyymmdd
            PROGRAMNAME=getdata
            START-OF-FIELDS
            PX_LAST_EOD
            LAST_UPDATE_DATE_EOD
            END-OF-FIELDS
            TIMESTARTED=Sat Aug 22 05:27:19 BST 2026
            START-OF-DATA
            MFGEPIC SW Equity|0|2|15.360|20260820|
            VOD LN Equity|0|2|see note|20260820|
            END-OF-DATA
            TIMEFINISHED=Sat Aug 22 05:27:23 BST 2026
            END-OF-FILE
            """;

    /**
     * {@code N.A.} and {@code N.S.} are absent values, not text.
     * <p>
     * Worth checking rather than declining, because the alternative reading is
     * available and wrong: an adapter that handed {@code "N.A."} to the loader as
     * a {@code DECIMAL} would fail the load, and one that handed it over as text
     * would put the string in a numeric column or thereabouts. Reading it as
     * nothing is a decision, and this is where it is recorded.
     */
    @Override
    protected List<Absence> absences() {
        return List.of(new Absence("Bloomberg has no price for either security",
                NOTHING_PRICED.getBytes(US_ASCII), "price"));
    }

    /**
     * A failure names the security rather than the record's position.
     * <p>
     * The format is luckier than most here: a data line begins with the security
     * it is about, so an operator gets something they can grep the reply for
     * instead of an ordinal they would have to count out of a file of forty
     * thousand lines. That is why the expected text is the identifier and not a
     * number.
     */
    @Override
    protected List<Breakage> breakages() {
        return List.of(new Breakage("a price that is not a number",
                PRICE_THAT_IS_NOT_A_NUMBER.getBytes(US_ASCII), "VOD LN Equity"));
    }

    private static InputSpec spec(Map<String, String> properties, Locator locator,
                                  FieldSelectorSpec... fields) {
        return new InputSpec(MIME_TYPE, List.of(records(locator, fields)), List.of(), properties);
    }

    private static RecordSelectorSpec records(Locator locator, FieldSelectorSpec... fields) {
        return new RecordSelectorSpec("all", locator, List.of(fields));
    }
}
