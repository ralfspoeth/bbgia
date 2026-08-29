import io.github.ralfspoeth.xldr.bbgia.BbgImpInputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.xldr.bbgia {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires static org.jspecify;
    provides InputAdapterFactory with BbgImpInputAdapterFactory;
}