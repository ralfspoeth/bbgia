package io.github.ralfspoeth.xldr.bbgia;

import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a Bloomberg Data License reply file - the {@code .out} of a
 * {@code getdata} request.
 *
 * <pre>
 * START-OF-FILE
 * RUNDATE=20260822          &lt;- header tags, name=value
 * DATEFORMAT=yyyymmdd
 * START-OF-FIELDS
 * VOLATILITY_162W           &lt;- the fields requested, in order
 * LAST_UPDATE_DATE_EOD
 * END-OF-FIELDS
 * START-OF-DATA
 * MFGEPIC SW Equity|0|2|15.360|20260820|
 * END-OF-DATA
 * END-OF-FILE
 * </pre>
 * <p>
 * A data line is pipe-separated and begins with three values every reply
 * carries, whatever was asked for: the security as it was requested, the return
 * code, and how many fields follow. Those three are addressable as
 * {@code #id}, {@code #retCode} and {@code #fieldCount} - names beginning with
 * {@code #} because a Bloomberg field name cannot.
 * <p>
 * The data section is flat, so a record selector says which lines are its own
 * with a {@code discriminator} and never with a {@code selector}: there is one
 * section and nothing to point at. One request may ask for equities and bonds
 * together, and this is how the two are told apart:
 *
 * <pre>
 * "recordSelectors": [
 *   { "name": "equity", "discriminator": { "selector": "#id", "matches": ".*Equity" }, ... }
 * ]
 * </pre>
 *
 * <h2>One row per field</h2>
 * <p>
 * A record selector whose field selectors include {@code #field} or
 * {@code #value} produces a row for every field rather than one wide row per
 * security:
 *
 * <pre>
 * { "name": "quotes", "fieldSelectors": [
 *     {"name": "security", "selector": "#id"},
 *     {"name": "field",    "selector": "#field"},
 *     {"name": "value",    "selector": "#value"}
 * ]}
 * </pre>
 * <p>
 * The fields walked are the ones the <em>file</em> declares between
 * {@code START-OF-FIELDS} and {@code END-OF-FIELDS}, not ones the spec names,
 * which is the reason to do it this way: a request whose field list changes
 * loads into the same table without the spec being touched, and a table of
 * three columns outlives a request that a wide one would not.
 * <p>
 * A field with no value yields no row, so such a selector needs no
 * {@code skipEmptyRecords} - a security that returned nothing produces nothing.
 * {@code #value} is {@code TEXT} unless the spec says otherwise, and usually
 * should be: the fields of one reply have different types and one column cannot
 * carry them typed.
 * <p>
 * Naming an ordinary field alongside these is allowed rather than refused. It
 * repeats that field's value on every row of the security, which is wrong often
 * enough to mention and right often enough - a currency beside each quote -
 * that the adapter does not get to decide.
 *
 * <h2>Properties</h2>
 * <p>
 * {@code charset} (default {@code US-ASCII}, which is what Data License
 * delivers), and the three every adapter takes - {@code dateFormat},
 * {@code numberFormat}, {@code locale} - applied through {@link Formats}. Where
 * {@code dateFormat} is left out, the file's own {@code DATEFORMAT} tag decides;
 * see {@link #javaDatePattern}.
 * <p>
 * {@code skipEmptyRecords} (default {@code false}) drops a data line none of
 * whose <em>real</em> fields came back with a value - real meaning a field of
 * the line past the three it always begins with, and counted over what the
 * record selector declares rather than over what one mapping pulls. Those three are
 * excluded because {@code #id} is on every line and in every mapping, so
 * counting it would mean no line is ever empty; a {@code tag:} is excluded for
 * the same reason one level up, being the same on every line of the file. A
 * value is missing if it is absent, blank, {@code N.S.} or {@code N.A.}, and
 * the test reads the text rather than the converted value, so a line is dropped
 * or kept on what the reply says.
 *
 * <h2>One limit worth knowing</h2>
 * <p>
 * The records are streamed, so a {@code tag:} selector sees the tags written
 * <em>before</em> {@code START-OF-DATA} - which is all of them but
 * {@code TIMEFINISHED}. Reading that one would mean buffering every record until
 * the end of the file to answer a question about the first, and it is a
 * diagnostic rather than data. {@code TIMESTARTED} sits before the data section
 * and is available.
 */
class BbgInputAdapter implements InputAdapter {

    private static final Pattern PIPE = Pattern.compile("\\|");

    /**
     * the prefix that says a selector names a header tag rather than a field
     */
    private static final String TAG = "tag:";

    /**
     * the tag by which a reply declares how it spells its dates
     */
    private static final String DATE_FORMAT_TAG = "DATEFORMAT";

    private static final String CHARSET = "charset";

    /**
     * Drop a data line none of whose requested real fields came back with a
     * value. Off by default: turning it on changes what a load inserts, and a
     * spec that wants fewer rows should have to say so.
     */
    private static final String SKIP_EMPTY = "skipEmptyRecords";

    /**
     * The two addresses that turn a record selector into one row per field -
     * see {@link Address.FieldName}.
     */
    private static final String FIELD = "#field";
    private static final String VALUE = "#value";

    /**
     * The ways a reply says nothing, in one place because two places would
     * drift. {@code N.S.} is not subscribed and {@code N.A.} not available;
     * blank is a field the line returned empty, which includes the last one,
     * since a trailing empty value leaves no text after the final pipe.
     */
    private static boolean absent(@Nullable String text) {
        return text == null || text.isBlank() || "N.S.".equals(text) || "N.A.".equals(text);
    }

    /**
     * The three values every data line begins with, addressable by name in every
     * record selector without being declared.
     * <p>
     * Ordered, and so is everything built from it, so that {@code Result.fields()}
     * comes out the same on every run. The loader binds by name and would not
     * care, but a {@code Map.of} here would have made two runs of one file print
     * their fields in different orders, which costs an afternoon the first time
     * it is mistaken for a real difference.
     */
    private static final Map<String, Address> ALWAYS = always();

    private static Map<String, Address> always() {
        var fixed = new LinkedHashMap<String, Address>();
        fixed.put("#id", new Address.Named("#id", DataType.TEXT));
        fixed.put("#retCode", new Address.Named("#retCode", DataType.INTEGRAL));
        fixed.put("#fieldCount", new Address.Named("#fieldCount", DataType.INTEGRAL));
        return Collections.unmodifiableMap(fixed);
    }

    /**
     * Where a value sits in a reply file, which is one of three places.
     * <p>
     * Named {@code Address} rather than {@code Selector} so that the spec's
     * {@link Selector} - what the author wrote - keeps its own name in this file.
     * This is what that resolves to.
     */
    sealed interface Address {

        DataType dataType();

        /**
         * a header tag, written {@code tag:RUNDATE}; one value for the whole file
         */
        record Tag(String name, DataType dataType) implements Address {}

        /**
         * a data field by the name the file gives it in {@code START-OF-FIELDS}
         */
        record Named(String name, DataType dataType) implements Address {}

        /**
         * The n-th value of the data line, 0-based here and written {@code nth}
         * in the spec, which counts from one. The first three are the fixed ones,
         * so {@code nth: 4} is the first requested field.
         */
        record At(int index, DataType dataType) implements Address {}

        /**
         * The name of the field a row is about, written {@code #field}.
         * <p>
         * Declaring this or {@link FieldValue} puts a record selector into
         * one-row-per-field mode: instead of one wide row carrying every
         * requested field, a data line yields a row for each field the
         * <em>file</em> declares in {@code START-OF-FIELDS}, carrying the fixed
         * three, the field's name and its value. The spec therefore does not
         * name the fields at all, which is the point - a request that changes
         * its field list does not need the spec edited to follow it.
         */
        record FieldName(DataType dataType) implements Address {}

        /**
         * The value of the field a row is about, written {@code #value}.
         * <p>
         * {@code TEXT} unless a spec says otherwise, and it should rarely say
         * otherwise: the fields of one reply have different types and one column
         * cannot carry them typed. Deferring that is what the shape is for.
         */
        record FieldValue(DataType dataType) implements Address {}
    }

    /**
     * The field a row is about, in one-row-per-field mode, and {@code null} in
     * the ordinary wide one.
     */
    private record Cell(String name, int index) {}

    /**
     * What one file's header said: the tags, where each field sits in a data
     * line, and how this file spells its numbers and dates.
     * <p>
     * A record rather than fields of the adapter, because a {@link Row} is
     * resolved lazily and has to keep hold of the header its line came from. As
     * instance state this was wrong twice over: the loader calls {@link #parse}
     * once per record mapping on the same adapter, so the second call cleared
     * the maps the first call's rows still resolved against, and an adapter is
     * required to hold no mutable state across calls at all.
     */
    private record Header(Map<String, String> tags, Map<String, Integer> fieldIndex, Formats formats) {}

    /**
     * the header, and whether a data section follows it
     */
    private record Head(Header header, boolean dataFollows) {}

    /**
     * A discriminator and the place it looks, resolved once when the adapter is
     * built rather than per line.
     * <p>
     * It tests the raw text, before any conversion: {@code equals} holds a string
     * and {@code matches} a pattern, so a line is kept or dropped on what the
     * file says rather than on what it would become.
     */
    private record Test(Discriminator discriminator, Address at) {
        boolean accepts(String[] line, Header header) {
            // no Cell: a discriminator chooses a data line, which happens before
            // that line is split into one row per field and is the same question
            // either way
            return discriminator.accepts(raw(at, line, header, null));
        }
    }

    /**
     * One record selector, resolved: which lines are its own, and where each of
     * its fields sits.
     * <p>
     * {@code test} is null for {@link Locator.Every}, which keeps every line.
     * {@link Locator.At} is not among the possibilities because the constructor
     * refuses it - a reply file has one data section and no way to be pointed at
     * part of it - so there is no third case here to write and get wrong.
     */
    private record Kind(@Nullable Test test, Map<String, Address> addresses, boolean perField) {
        boolean selects(String[] line, Header header) {
            return test == null || test.accepts(line, header);
        }

        /**
         * The addresses of this selector that point at a field of the data line
         * beyond the fixed three - what "real" means for the skip.
         * <p>
         * A tag is not one: it has the same value on every line of the file, so
         * counting it would mean no line is ever empty. Nor are the fixed three,
         * for the same reason one step down - {@code #id} is always there, so a
         * mapping that carries it, which is every mapping, would keep every row.
         * <p>
         * Taken from what the <em>selector declares</em> rather than from what
         * the current mapping asked for. "Did this security answer?" is a
         * question about the data line, and a mapping that pulls only
         * {@code #id} out of a selector declaring four fields must not thereby
         * make every line empty - which is what reading the requested set did,
         * and what the test for {@code #id} not keeping a line alive now holds
         * down.
         */
        List<Address> realFieldsDeclared(Header header) {
            return addresses.values().stream().filter(address -> switch (address) {
                case Address.Named(var field, _) -> header.fieldIndex().getOrDefault(field, -1) >= 3;
                case Address.At(var index, _) -> index >= 3;
                case Address.Tag _ -> false;
                case Address.FieldName _ -> false;
                case Address.FieldValue _ -> false;
            }).toList();
        }
    }

    private final Map<String, Kind> kinds = new LinkedHashMap<>();
    private final Map<String, String> properties;
    private final Charset charset;

    /**
     * Whether the author named a date pattern. If so it wins over the file's own
     * {@code DATEFORMAT}: they have said what these dates are, in the syntax the
     * rest of the toolkit uses, and a spec is more specific than a convention.
     */
    private final boolean dateDeclared;

    /**
     * whether a line none of whose requested real fields has a value is dropped
     */
    private final boolean skipEmpty;

    BbgInputAdapter(InputSpec inputSpec) {
        this.properties = Map.copyOf(inputSpec.properties());
        this.dateDeclared = properties.containsKey(Formats.DATE_FORMAT);
        this.skipEmpty = Boolean.parseBoolean(properties.getOrDefault(SKIP_EMPTY, "false"));
        this.charset = properties.containsKey(CHARSET)
                ? Charset.forName(properties.get(CHARSET))
                : StandardCharsets.US_ASCII;
        inputSpec.recordSelectors().forEach(rs -> {
            var forRecord = new LinkedHashMap<>(ALWAYS);
            rs.fieldSelectors().forEach(fs -> forRecord.put(fs.name(), addressOf(fs)));
            // putIfAbsent and not put: two record selectors of one name would
            // otherwise leave the second silently in place of the first, and a
            // mapping naming it could not have said which it meant. The CSV
            // adapter in xldr had this exact hole until 0.51, where it was found
            // by the conformance kit rather than by anyone reading the code
            var perField = forRecord.values().stream()
                    .anyMatch(a -> a instanceof Address.FieldName || a instanceof Address.FieldValue);
            if (skipEmpty && !perField && noRealFieldsIn(rs)) {
                // the skip would drop every line of this selector, and the load
                // would report success having inserted nothing. Refused here
                // rather than discovered by a count of zero at four in the
                // morning
                throw new IllegalArgumentException("record selector '" + rs.name() + "' declares no field"
                        + " beyond #id, #retCode and #fieldCount, so with " + SKIP_EMPTY
                        + " every one of its lines would be dropped");
            }
            var kind = new Kind(testOf(rs), Collections.unmodifiableMap(forRecord), perField);
            if (kinds.putIfAbsent(rs.name(), kind) != null) {
                throw new IllegalArgumentException("two record selectors are named '" + rs.name()
                        + "'; a mapping names one of them and could not say which");
            }
        });
    }

    /**
     * Which lines belong to this record selector.
     * <p>
     * A {@code selector} is refused here rather than at four in the morning: the
     * data section of a reply is the records, so there is nothing for an XPath or
     * a pointer to address, and a spec that writes one has confused this format
     * with a tree. {@link Locator#wrongBecause} phrases the complaint and shows
     * the discriminator that was meant.
     */
    private static @Nullable Test testOf(RecordSelectorSpec rs) {
        return switch (rs.locator()) {
            case Locator.Every _ -> null;
            case Locator.Where(var discriminator) ->
                    new Test(discriminator, addressOf(discriminator.at(), DataType.TEXT));
            case Locator.At at -> throw at.wrongBecause(rs.name(),
                    "a reply file has one data section and nothing to point at");
        };
    }

    /**
     * What the author wrote, resolved to where it will be looked for.
     * <p>
     * A {@code selector} beginning with {@code tag:} is a header tag; anything
     * else is the name of a field, spelled as the file spells it in
     * {@code START-OF-FIELDS}. There is no prefix for the ordinary case because
     * {@code PX_LAST_EOD} is what a Bloomberg user calls that field, and the two
     * namespaces need only one marker between them to stay apart.
     * <p>
     * An {@code nth} counts the values of the data line from one, which is what
     * {@link Selector.Nth} means for every separated format. A pipe-separated
     * line has components in exactly that sense, so this reads them rather than
     * refusing them - and it is the only way to address a field the header names
     * twice.
     */
    private static Address addressOf(FieldSelectorSpec fs) {
        return addressOf(fs.selector(), fs.dataType() == null ? DataType.TEXT : fs.dataType());
    }

    private static Address addressOf(Selector selector, DataType type) {
        return switch (selector) {
            case Selector.Text(var text) when FIELD.equals(text) -> new Address.FieldName(type);
            case Selector.Text(var text) when VALUE.equals(text) -> new Address.FieldValue(type);
            case Selector.Text(var text) when text.startsWith(TAG) ->
                    new Address.Tag(text.substring(TAG.length()), type);
            case Selector.Text(var text) -> new Address.Named(text, type);
            case Selector.Nth nth -> new Address.At(nth.index(), type);
        };
    }

    /**
     * Whether a record selector addresses nothing past the fixed three, which
     * is what makes {@link #SKIP_EMPTY} unsatisfiable for it.
     */
    private static boolean noRealFieldsIn(RecordSelectorSpec rs) {
        return rs.fieldSelectors().stream().noneMatch(fs -> switch (addressOf(fs)) {
            case Address.Named(var name, _) -> !ALWAYS.containsKey(name);
            case Address.At(var index, _) -> index >= 3;
            case Address.Tag _ -> false;
            case Address.FieldName _ -> false;
            case Address.FieldValue _ -> false;
        });
    }

    /**
     * The text at one address, or {@code null}.
     * <p>
     * Null in three ways, all of them an absent value rather than a failure: a
     * tag the file does not carry, a field it did not return, and a line that
     * stops short of that position - which happens because a trailing empty
     * value leaves no text after the last pipe.
     */
    private static @Nullable String raw(@Nullable Address address, String[] line, Header header,
                                        @Nullable Cell cell) {
        return switch (address) {
            case null -> null;
            case Address.Tag(var name, _) -> header.tags().get(name);
            case Address.Named(var name, _) -> at(line, header.fieldIndex().getOrDefault(name, -1));
            case Address.At(var index, _) -> at(line, index);
            // null where a wide selector declared one of these, which the
            // constructor does not forbid: it makes the selector per-field, so
            // there is always a cell by the time a row is resolved
            case Address.FieldName _ -> cell == null ? null : cell.name();
            case Address.FieldValue _ -> cell == null ? null : at(line, cell.index());
        };
    }

    /**
     * The security a data line is about, for a complaint to name.
     * <p>
     * Position zero, which {@code #id} also resolves to - taken directly rather
     * than through {@link #raw} because this is called from a failure path, where
     * a second lookup that could itself fail would replace the message with a
     * worse one.
     */
    private static String security(String[] line) {
        return line.length > 0 && !line[0].isBlank()
                ? "'" + line[0] + "'"
                : "a data line carrying no identifier";
    }

    /**
     * the value at a position, or null where the line has no such position
     */
    private static @Nullable String at(String[] line, int index) {
        return index >= 0 && index < line.length ? line[index] : null;
    }

    private static @Nullable Object value(String name, @Nullable Address address,
                                          String[] line, Header header, @Nullable Cell cell) {
        if (address == null) {
            return null;
        } else {
            var text = raw(address, line, header, cell);
            // absent() rather than a second list of the ways a reply says
            // nothing, so that this and the skip cannot come to disagree
            if (absent(text)) {
                return null;
            }
            try {
                // through Formats rather than parsing here, so that dateFormat,
                // numberFormat and locale mean in this format what they mean in
                // every other one
                return header.formats().parse(address.dataType(), text);
            } catch (RuntimeException e) {
                // Obligation 7 of the SPI: say which record it was. This format is
                // luckier than most - a data line begins with the security it is
                // about, so the complaint can name a thing an operator recognises
                // and can grep the reply for, rather than an ordinal they would
                // have to count out of a file of forty thousand lines.
                throw new IllegalStateException("cannot read field '" + name + "' of "
                        + security(line) + " as " + address.dataType()
                        + ", from '" + text + "': " + e.getMessage(), e);
            }
        }
    }

    /**
     * The {@code java.time} pattern for the way this reply spells its dates.
     * <p>
     * Bloomberg writes the month lower-case - {@code yyyymmdd} - where
     * {@link java.time.format.DateTimeFormatter} reads {@code mm} as the minute
     * of the hour. Handed over untranslated it would not fail; it would parse
     * {@code 20260820} into a nonsense date, or throw at four in the morning. So
     * the spellings a reply actually uses are named here, and anything else is
     * refused with the way to say it.
     *
     * @param declared the file's {@code DATEFORMAT} tag, absent in an old reply
     */
    private static String javaDatePattern(@Nullable String declared) {
        var spelling = declared == null ? "yyyymmdd" : declared.strip().toLowerCase(Locale.ROOT);
        return switch (spelling) {
            case "yyyymmdd" -> "yyyyMMdd";
            case "mm/dd/yyyy" -> "MM/dd/yyyy";
            case "dd/mm/yyyy" -> "dd/MM/yyyy";
            default -> throw new IllegalArgumentException(DATE_FORMAT_TAG + "='" + declared
                    + "' is not a spelling this adapter knows; set the '" + Formats.DATE_FORMAT
                    + "' property to the java.time pattern that reads it");
        };
    }

    private Formats formatsFor(Map<String, String> tags) {
        if (dateDeclared) {
            return Formats.of(properties);
        }
        var merged = new LinkedHashMap<>(properties);
        merged.put(Formats.DATE_FORMAT, javaDatePattern(tags.get(DATE_FORMAT_TAG)));
        return Formats.of(merged);
    }

    private enum State {TAGS, FIELDS}

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var kind = kinds.get(recordSelector);
        if (kind == null) {
            throw new IllegalArgumentException("no record selector named " + recordSelector
                    + "; the input spec declares " + kinds.keySet());
        }
        var unknown = fieldSelectors.stream().filter(name -> !kind.addresses().containsKey(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + recordSelector
                    + " declares no field selector(s) " + unknown);
        }

        // the reader is not closed here: the stream belongs to the caller, who
        // closes it once every record mapping of the file has been read
        var lines = new BufferedReader(new InputStreamReader(source, charset));
        var head = readHeader(lines);
        var header = head.header();

        var fields = fieldSelectors.stream()
                .sorted()
                .map(name -> new Field(name, kind.addresses().get(name).dataType().clazz()))
                .toList();

        Stream<Row> rows;
        if (!head.dataFollows()) {
            rows = Stream.empty();
        } else {
            var selected = lines.lines()
                    .takeWhile(line -> !"END-OF-DATA".equals(line))
                    .map(PIPE::split)
                    .filter(line -> kind.selects(line, header));
            if (kind.perField()) {
                // one row per field the file declares, not per field the spec
                // names - a changed request is then a changed set of rows
                // rather than a spec to edit. A field with no value yields no
                // row, which is also why this mode needs no skipEmptyRecords:
                // a security that returned nothing produces nothing
                var cells = realFields(header);
                rows = selected.flatMap(line -> cells.stream()
                        .filter(cell -> !absent(at(line, cell.index())))
                        .map(cell -> (Row) name ->
                                value(name, kind.addresses().get(name), line, header, cell)));
            } else {
                var real = kind.realFieldsDeclared(header);
                rows = selected
                        .filter(line -> !skipEmpty || real.stream()
                                .anyMatch(address -> !absent(raw(address, line, header, null))))
                        .map(line -> (Row) name ->
                                value(name, kind.addresses().get(name), line, header, null));
            }
        }

        return new Result(fields, rows);
    }

    /**
     * The fields the reply declared, in the order it declared them, excluding
     * the three every data line begins with.
     * <p>
     * This is what a one-row-per-field selector walks, and taking it from the
     * header rather than from the spec is the whole point of that mode.
     */
    private static List<Cell> realFields(Header header) {
        return header.fieldIndex().entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .map(e -> new Cell(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Everything before the records: the tags, the field order, and the formats
     * they imply.
     * <p>
     * Read eagerly, and only this much. A reply states its fields before its
     * data, so by {@code START-OF-DATA} everything a row needs is known and the
     * records can be streamed - which is the whole reason to stop here rather
     * than read the file.
     */
    private Head readHeader(BufferedReader lines) throws IOException {
        var tags = new LinkedHashMap<String, String>();
        var fieldIndex = new LinkedHashMap<String, Integer>();
        fieldIndex.put("#id", 0);
        fieldIndex.put("#retCode", 1);
        fieldIndex.put("#fieldCount", 2);

        var state = State.TAGS;
        int nextField = 3;
        int lineNumber = 0;
        boolean dataFollows = false;

        String line;
        while ((line = lines.readLine()) != null) {
            lineNumber++;
            if ("START-OF-DATA".equals(line)) {
                dataFollows = true;
                break;
            }
            if ("END-OF-FILE".equals(line)) {
                // a reply that asked for nothing, or found nothing: a header and
                // no records, which is an empty load rather than a failure
                break;
            }
            switch (line) {
                case "START-OF-FILE", "END-OF-FIELDS" -> state = State.TAGS;
                case "START-OF-FIELDS" -> state = State.FIELDS;
                default -> {
                    switch (state) {
                        case TAGS -> {
                            var equals = line.indexOf('=');
                            if (equals < 0) {
                                throw new IOException("line " + lineNumber + " is in the header and is"
                                        + " not name=value: '" + line + "'");
                            }
                            tags.put(line.substring(0, equals), line.substring(equals + 1));
                        }
                        case FIELDS -> fieldIndex.put(line, nextField++);
                    }
                }
            }
        }

        return new Head(
                new Header(
                        Collections.unmodifiableMap(tags),
                        Collections.unmodifiableMap(fieldIndex),
                        formatsFor(tags)),
                dataFollows);
    }
}
