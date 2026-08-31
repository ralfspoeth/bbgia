package io.github.ralfspoeth.xldr.bbgia.test;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * This adapter against the published conformance kit.
 * <p>
 * Six of the ten obligations in {@code ia}'s package documentation are checkable
 * without knowing the format, and extending this is the whole of running them.
 * It is worth more here than for an adapter inside the xldr reactor: this one
 * lives in its own repository, against a released version of the SPI, and the
 * obligations are prose that nothing else enforces. The swift-mt adapter was
 * written the same way and diverged on one of them.
 * <p>
 * The other four stay in {@link BbgInputAdapterTest}, being about what this
 * format cannot mean: a {@code selector} where there is nothing to point at, a
 * discriminator over the one data section, and a {@code DATEFORMAT} spelling
 * nobody can translate.
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
    protected InputAdapterFactory factory() {
        return InputAdapterFactory.of(new InputSpec(MIME_TYPE, List.of(), List.of(), Map.of()))
                .orElseThrow(() -> new IllegalStateException(
                        "no factory reads " + MIME_TYPE + "; is the bbgia module in the graph?"));
    }

    @Override
    protected String mimeType() {
        return MIME_TYPE;
    }

    /**
     * One value of each addressing this format has - a fixed leading value, a
     * header tag, and two fields by name - and four of the five types, so that
     * the typing obligations bite rather than passing on a spec that is all
     * text.
     */
    @Override
    protected InputSpec spec() {
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
    protected byte[] sample() {
        return SAMPLE.getBytes(US_ASCII);
    }

    @Override
    protected List<Refusal> refusals() {
        return List.of(); // todo
    }
}
