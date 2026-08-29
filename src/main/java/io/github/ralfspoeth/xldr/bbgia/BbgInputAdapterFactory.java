package io.github.ralfspoeth.xldr.bbgia;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Objects;

public class BbgInputAdapterFactory implements InputAdapterFactory {
    @Override
    public boolean reads(String mimeType) {
        return Objects.equals("application/x-bloomberg-out", mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new BbgInputAdapter(spec);
    }
}
