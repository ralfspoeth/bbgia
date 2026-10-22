# Changelog

Notable changes per release, newest first.

Before `1.0` the version has two components and the second carries everything: a release that only fixes something
and a release that adds one both move it, because inventing a patch component to classify one change would be more
ceremony than this project has earned. What a release contains is written here rather than inferred from its number.

The versions are the git tags `bbgia-<version>`. Nothing is published to Maven Central; each release is a jar
attached to the GitHub release of the same name.

## 0.2

Two ways of reading a reply that a wide table could not express, and the deployment shape that never worked.

Neither addition changes the mapping-spec format. The adapter properties are a free-form map, and a name beginning
with `#` is already a namespace a Bloomberg field name cannot enter, so there is no new schema and nothing in xldr
changes.

### Added

- **`skipEmptyRecords` drops a security that answered nothing.** A data line is dropped when none of the record
  selector's *real* fields came back with a value - real meaning a field of the line past the three it always
  begins with, and missing meaning absent, blank, `N.S.` or `N.A.`. Off by default, because turning it on changes
  what a load inserts. The test reads the raw text rather than the converted value, so it cannot itself throw, and
  a line that survives still fails loudly on a value that will not convert.

  **The exclusions are the whole of making it work.** `#id` is on every line and in every mapping, so counting it
  would mean no line is ever dropped and the property would look broken; a `tag:` is the same on every line of the
  file and would do the same one level up. And the test is over what the record selector *declares*, not over what
  one mapping pulls out of it: "did this security answer?" is a question about the data line, and a mapping taking
  only `#id` from a four-field selector must not thereby make every line empty.

  A record selector declaring nothing past the fixed three is refused when the adapter is built, that being the
  case the skip would empty entirely while reporting success.

- **`#field` and `#value` read one row per field.** A record selector naming either produces a row for every field
  rather than one wide row per security, carrying the fixed three, the field's name and its value - so a
  three-column table holds any reply at all.

  The fields walked are the ones the *file* declares between `START-OF-FIELDS` and `END-OF-FIELDS`, not ones the
  spec names, and that is the reason to do it this way: a request whose field list changes loads into the same
  table without the spec being edited, and that table outlives a wide one. A field with no value yields no row, so
  this mode wants no `skipEmptyRecords` of its own - a security that returned nothing produces nothing. `#value` is
  `TEXT` unless the spec says otherwise, and usually should be, the fields of one reply having different types that
  one column cannot carry.

  Naming an ordinary field alongside these is allowed rather than refused. It repeats that field's value on every
  row of the security, which is probably a mistake and is sometimes exactly right - a currency beside each quote -
  and the adapter is not in a position to tell which.

### Fixed

- **The adapter was invisible on a classpath.** It declared itself only with `provides` in its `module-info`, and
  that directive is read only for a resolved *named* module - so the jar in a war, or in anything Quarkus builds,
  landed in the unnamed module and had its declaration ignored entirely. Such a deployment found no adapter for
  `application/x-bloomberg-out` and refused every spec naming it, with nothing to suggest the jar was present and
  unread. A `META-INF/services` entry now carries the same line for that case.

  One of the two declarations is dead weight in any given deployment and there is no telling in advance which. xldr
  reached the same conclusion for its five bundled adapters at 1.3.0; this module was released before that and did
  not catch up until now.

### Changed

- **The README no longer promises the module path specifically**, which is what made the gap above invisible for a
  release. It now says either path, and says why there are two declarations, so that whoever meets the duplication
  next does not tidy one of them away.

- **The parent moves to `plumbum` 3.0.4**, the xldr BOM to 1.3.1 and the H2 test dependency to 2.5.252.

## 0.1

First release, recorded here after the fact: the adapter, its reading of a Data License reply, the three fixed
leading values, `tag:` and `nth` addressing, and the discriminator that tells one record kind from another in a
mixed reply.
