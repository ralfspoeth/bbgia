package io.github.ralfspoeth.xldr.bbgia;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Set;

public class BbgImpInputAdapterFactory implements InputAdapterFactory {
    @Override
    public boolean reads(String mimeType) {
        return Set.of("application/x-bbg-imp", "text/plain").contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new BbgImpInputAdapter(spec);
    }
}
