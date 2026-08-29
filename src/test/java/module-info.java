import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

open module io.github.ralfspoeth.xldr.bbgia.test {
    requires transitive io.github.ralfspoeth.xldr.ia;
    requires io.github.ralfspoeth.xldr.bbgia;
    requires org.junit.jupiter.api;
    uses InputAdapterFactory;
}