import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;

open module io.github.ralfspoeth.xldr.bbgia.test {
    requires transitive io.github.ralfspoeth.xldr.ia;
    // the provider has to be in the graph or ServiceLoader will not see it:
    // nothing here names a bbgia class, the whole point being that an adapter
    // is reached through the SPI
    requires io.github.ralfspoeth.xldr.bbgia;
    // brings ia and junit-jupiter-api with it, both being transitive there
    requires io.github.ralfspoeth.xldr.tck;
    uses InputAdapterFactory;
}