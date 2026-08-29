package io.github.ralfspoeth.xldr.bbgia;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

class BbgInputAdapter implements InputAdapter {

    // the pipe | character
    private static final Pattern PIPE = Pattern.compile("\\|");

    // three field-level selectors always present
    private static final Map<String, Selector.FieldSelector> ALWAYS = Map.of(
            "#id", new Selector.FieldSelector("#id", DataType.TEXT),
            "#retCode", new Selector.FieldSelector("#retCode", DataType.INTEGRAL),
            "#fieldCount", new Selector.FieldSelector("#fieldCount", DataType.INTEGRAL)
    );

    // the tags, read with the incoming stream
    private final Map<String, String> tags = new HashMap<>();

    private void rebuildTags() {
        tags.clear();
    }

    // the fields, read with the incoming stream; first three fix
    private final Map<String, Integer> fieldIndexMap = new HashMap<>();

    private void rebuildFieldIndexMap() {
        fieldIndexMap.clear();
        fieldIndexMap.putAll(Map.of("#id", 0, "#retCode", 1, "#fieldCount", 2));
    }

    private void reset() {
        rebuildTags();
        rebuildFieldIndexMap();
    }

    sealed interface Selector {
        DataType dataType();

        record TagSelector(String name) implements Selector {
            @Override
            public DataType dataType() {
                return DataType.TEXT;
            }
        }

        record FieldSelector(String name, DataType dataType) implements Selector {}
    }

    private @Nullable Object textual(String[] row, Selector selector) {
        return switch (selector) {
            case Selector.TagSelector(String name) -> tags.get(name);
            case Selector.FieldSelector(String name, DataType type) -> {
                var index = fieldIndexMap.getOrDefault(name, -1);
                var tmp = index > -1 ? row[index] : null;
                yield switch (tmp) {
                    case "N.S.", "N.A." -> null;
                    case null -> null;
                    default -> switch (type) {
                        case TEMPORAL -> LocalDate.parse(tmp, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
                        case DECIMAL -> new BigDecimal(tmp);
                        case INTEGRAL -> Long.parseLong(tmp);
                        case FP -> Double.parseDouble(tmp);
                        case TEXT -> tmp;
                    };
                };
            }
        };
    }

    // build from the input spec; three always in
    private final Map<String, Map<String, Selector>> selectorMap = new HashMap<>();


    public BbgInputAdapter(InputSpec inputSpec) {
        inputSpec.recordSelectors().forEach(rs -> {
            var m = selectorMap.computeIfAbsent(rs.name(), _ -> new HashMap<>(ALWAYS));
            rs.fieldSelectors().forEach(fs -> {
                if (fs.selector() instanceof io.github.ralfspoeth.xldr.spec.Selector.Text(var s)) {
                    if (s.startsWith("tag:")) {
                        m.put(fs.name(), new Selector.TagSelector(s.substring(4)));
                    } else {
                        m.put(fs.name(), new Selector.FieldSelector(s,
                                fs.dataType() == null ? DataType.TEXT : fs.dataType()
                        ));
                    }
                }
            });
        });
    }


    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        enum State {
            TAGS,
            FIELDS,
            DATA,
            END
        }
        State state = State.TAGS;
        reset();

        int currentIndex = 3;

        final List<String[]> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                switch (line) {
                    case "START-OF-FILE", "END-OF-FIELDS", "END-OF-DATA" -> state = State.TAGS;
                    case "START-OF-FIELDS" -> state = State.FIELDS;
                    case "START-OF-DATA" -> state = State.DATA;
                    case "END-OF-FILE" -> state = State.END;
                    default -> {
                        switch (state) {
                            case TAGS -> {
                                int indexEquals = line.indexOf('=');
                                var name = line.substring(0, indexEquals);
                                var value = line.substring(indexEquals + 1);
                                tags.put(name, value);
                            }
                            case FIELDS -> fieldIndexMap.put(line, currentIndex++);
                            case DATA -> rows.add(PIPE.split(line));
                            case END -> throw new IllegalStateException("Unexpected after end of file");
                        }
                    }
                }
            }
        }
        return ofNullable(selectorMap.get(recordSelector))
                .map(m -> new Result(
                                m.keySet().stream().filter(fieldSelectors::contains)
                                        .map(k -> new Field(k, m.get(k).dataType().clazz()))
                                        .toList(),
                                rows.stream()
                                        .map(strings -> new Row() {
                                            @Override
                                            public @Nullable Object get(String name) {
                                                return textual(strings, m.get(name));
                                            }
                                        })
                        )
                )
                .orElseGet(() -> new Result(List.of(), Stream.empty())
                );
    }
}
