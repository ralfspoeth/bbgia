# bbgia

An [xldr](https://github.com/ralfspoeth/xldr) input adapter for Bloomberg Data
License reply files - the `.out` a `getdata` request comes back as.

It is a format module and nothing else: put it on the module path and a spec
saying `"mimeType": "application/x-bloomberg-out"` is read by it, through
`ServiceLoader`. Nothing in xldr knows this module exists.

## The format

```
START-OF-FILE
RUNDATE=20260822          <- header tags, name=value
DATEFORMAT=yyyymmdd
PROGRAMNAME=getdata
START-OF-FIELDS
PX_LAST_EOD               <- the fields requested, in order
LAST_UPDATE_DATE_EOD
END-OF-FIELDS
TIMESTARTED=Sat Aug 22 05:27:19 BST 2026
START-OF-DATA
MFGEPIC SW Equity|0|2|15.360|20260820|
VOD LN Equity|0|2|N.A.|20260820|
END-OF-DATA
TIMEFINISHED=Sat Aug 22 05:27:23 BST 2026
END-OF-FILE
```

A data line is pipe-separated and begins with three values every reply carries,
whatever was asked for: the security as it was requested, the return code, and
how many fields follow. The requested fields follow in the order
`START-OF-FIELDS` gave them, and a trailing pipe closes the line.

## Addressing a value

Four spellings, and each says where to look rather than what to call it - the
name is the field selector's `name`, as everywhere else in a spec.

| `selector` | reads |
|---|---|
| `PX_LAST_EOD` | a field, spelled as `START-OF-FIELDS` spells it |
| `tag:RUNDATE` | a header tag; one value for the whole file |
| `#id`, `#retCode`, `#fieldCount` | the three fixed leading values |
| `nth: 4` | the 4th value of the line, counting from one |

There is no prefix for the ordinary case, because `PX_LAST_EOD` is what a
Bloomberg user calls that field. `tag:` is the one marker needed, the tags and
the fields being two namespaces that could otherwise collide. `#` starts the
fixed three because a Bloomberg field name cannot. And `nth` means here what it
means for every separated format in the toolkit - it is also the only way to
address a field the header happens to name twice.

```json
{
  "input": {
    "mimeType": "application/x-bloomberg-out",
    "recordSelectors": [
      {
        "name": "equity",
        "discriminator": { "selector": "#id", "matches": ".*Equity" },
        "fieldSelectors": [
          { "name": "id",      "selector": "#id" },
          { "name": "runDate", "selector": "tag:RUNDATE",           "type": "TEMPORAL" },
          { "name": "price",   "selector": "PX_LAST_EOD",           "type": "DECIMAL" },
          { "name": "asOf",    "selector": "LAST_UPDATE_DATE_EOD",  "type": "TEMPORAL" }
        ]
      }
    ]
  },
  "mapping": [ ... ]
}
```

## Which lines are of a kind

The data section is flat and there is one of it, so a record selector says which
lines are its own with a **`discriminator`** and never with a `selector`: there
is nothing to point at. A spec that writes one is refused when the adapter is
built, not when the file arrives.

One request may ask for equities and bonds together and they belong in different
tables, which is what the discriminator is for. A record selector that says
nothing keeps every line.

## Absent values

An absent value is `null`, and the loader binds SQL NULL. There are four ways to
get one, all of them ordinary:

- `N.S.` and `N.A.` - Bloomberg's *not supplied* and *not available*;
- a tag the file does not carry;
- a field the reply did not return;
- a line that stops short of that position, which happens because a trailing
  empty value leaves no text after the last pipe.

None of them fails the load. One security without a price should not cost you
the other ninety thousand.

## Properties

| property | default | means |
|---|---|---|
| `charset` | `US-ASCII` | what Data License delivers |
| `dateFormat` | the file's `DATEFORMAT` | a `java.time` pattern; see below |
| `numberFormat`, `locale` | none | as for every adapter, via `Formats` |

**Dates are the one place this format needs care.** A reply declares its own
spelling in the `DATEFORMAT` tag, and Bloomberg writes the month lower-case -
`yyyymmdd` - where `DateTimeFormatter` reads `mm` as the minute of the hour.
Handed over untranslated that does not fail; it quietly produces a nonsense
date. So the spellings a reply actually uses are translated by name
(`yyyymmdd`, `mm/dd/yyyy`, `dd/mm/yyyy`) and anything else is refused with the
instruction to set `dateFormat` yourself. A `dateFormat` in the spec always wins
over the tag: you have said what these dates are, in the syntax the rest of the
toolkit uses.

## One limit worth knowing

The records are streamed, so a `tag:` selector sees the tags written **before**
`START-OF-DATA` - which is all of them except `TIMEFINISHED`. Reading that one
would mean buffering every record to answer a question about the first, and it
is a diagnostic rather than data. `TIMESTARTED` sits before the data section and
is available.

## Building

```
mvn clean verify
```

Java 25 or later. The tests need no database and no network; `BbgConformanceTest`
runs the adapter against xldr's published
[conformance kit](https://github.com/ralfspoeth/xldr/tree/HEAD/tck), which
checks six of the ten obligations an adapter owes its caller. The other four -
what this format cannot mean, what a bad record looks like, whether it can tell
empty from absent, and what state it might have kept - are in
`BbgInputAdapterTest`, because no kit could know them.
