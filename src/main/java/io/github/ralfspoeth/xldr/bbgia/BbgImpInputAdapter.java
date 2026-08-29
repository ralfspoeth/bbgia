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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

class BbgImpInputAdapter implements InputAdapter {

    // the pipe | character
    private static final Pattern PIPE = Pattern.compile("\\|");

    // the tags, read with the incoming stream
    private final Map<String, String> tags = new HashMap<>();
    // the fields, read with the incoming stream; first three fix
    private final Map<String, Integer> fieldIndexMap = new HashMap<>(Map.of(
            "#id", 0, "#retCode", 1, "#fieldCount", 2)
    );

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

    private @Nullable String textual(String[] row, Selector selector) {
        return switch (selector) {
            case Selector.TagSelector(String name) -> tags.get(name);
            case Selector.FieldSelector(String name, DataType type) -> {
                var index = fieldIndexMap.getOrDefault(name, -1);
                yield index > -1 ? row[index] : null;
            }
        };
    }

    // build from the input spec; three always in
    private final Map<String, Selector> selectorMap = new HashMap<>(
            Map.of("#id", new Selector.FieldSelector("#id", DataType.TEXT),
                    "#retCode", new Selector.FieldSelector("#retCode", DataType.INTEGRAL),
                    "#fieldCount", new Selector.FieldSelector("#fieldCount", DataType.INTEGRAL)
            )
    );

    public BbgImpInputAdapter(InputSpec inputSpec) {
        inputSpec.recordSelectors().forEach(rs -> rs.fieldSelectors().forEach(fs -> {
            if (fs.selector() instanceof io.github.ralfspoeth.xldr.spec.Selector.Text(var s)) {
                if (s.startsWith("tag:")) {
                    selectorMap.put(fs.name(), new Selector.TagSelector(s.substring(4)));
                } else {
                    selectorMap.put(fs.name(), new Selector.FieldSelector(s,
                            fs.dataType() == null ? DataType.TEXT : fs.dataType()
                    ));
                }
            }
        }));
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
                            case FIELDS -> {
                                fieldIndexMap.put(line, currentIndex++);
                            }
                            case DATA -> {
                                var elems = PIPE.split(line);
                                rows.add(elems);
                            }
                        }
                    }
                }
            }
        }
        return new Result(
                selectorMap.keySet().stream().filter(fieldSelectors::contains)
                        .map(k -> new Field(k, selectorMap.get(k).dataType().clazz()))
                        .toList(),
                rows.stream()
                        .map(strings -> new Row() {
                                    @Override
                                    public @Nullable Object get(String name) {
                                        return textual(strings, selectorMap.get(name));
                                    }
                                }
                        )
        );
    }
}
