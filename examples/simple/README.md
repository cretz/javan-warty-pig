## Example: Simple

This example shows how to programmatically invoke the fuzzer and print out the unique branches. It fuzzes a parsing
method that takes a single number format string (i.e. `[+/-]##[.##]`) and returns a simple class of the pieces. It
starts with a single initial value of `+1.2`.

To run, execute the following from the JWP root project directory:

    path/to/gradle --no-daemon :examples:simple:run

It only executes for 5 seconds. Once complete, you'll get something like the following:

    Showing unique paths (with hit counts: false)
    Creating fuzzer
    Running fuzzer
    New path for param '+1.2', result: Num(neg=false, num=1, frac=2), hash: -458645911 (after 1 total execs)
    New path for param '*1.2', result: java.lang.NumberFormatException: No leading number(s), hash: 2084148829 (after 2 total execs)
    New path for param ';1.2', result: java.lang.NumberFormatException: No leading number(s), hash: 1080697836 (after 4 total execs)
    New path for param '+!.2', result: java.lang.NumberFormatException: No leading number(s), hash: -337144822 (after 13 total execs)
    New path for param '+1/2', result: java.lang.NumberFormatException: Unknown char: /, hash: -1167107520 (after 16 total execs)
    New path for param '+q.2', result: java.lang.NumberFormatException: No leading number(s), hash: -1352713057 (after 16 total execs)
    New path for param '+1n2', result: java.lang.NumberFormatException: Unknown char: n, hash: 2084203915 (after 18 total execs)
    New path for param '+1."', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: -1167107493 (after 23 total execs)
    New path for param '+1.:', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: 2084197254 (after 24 total execs)
    New path for param '-1.2', result: Num(neg=true, num=1, frac=2), hash: 241537519 (after 27 total execs)
    New path for param '31.2', result: Num(neg=false, num=31, frac=2), hash: 1654085244 (after 30 total execs)
    New path for param '+162', result: Num(neg=false, num=162, frac=), hash: 1108730124 (after 39 total execs)
    New path for param '7', result: Num(neg=false, num=7, frac=), hash: 2049475039 (after 269 total execs)
    New path for param '22&22222222222222222', result: java.lang.NumberFormatException: Unknown char: &, hash: 1911280397 (after 277 total execs)
    New path for param '+', result: java.lang.NumberFormatException: No leading number(s), hash: -337144836 (after 281 total execs)
    New path for param '1G', result: java.lang.NumberFormatException: Unknown char: G, hash: 1075128094 (after 311 total execs)
    New path for param '+1.', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: -1167107507 (after 367 total execs)
    New path for param '-', result: java.lang.NumberFormatException: No leading number(s), hash: 1080697858 (after 439 total execs)
    New path for param '+1.211.2.2', result: java.lang.NumberFormatException: Unknown char: ., hash: -940977001 (after 447 total execs)
    New path for param '11.-', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: 1911280424 (after 485 total execs)
    New path for param '--', result: java.lang.NumberFormatException: No leading number(s), hash: 1080697872 (after 556 total execs)
    New path for param '-@', result: java.lang.NumberFormatException: No leading number(s), hash: -337144871 (after 582 total execs)
    New path for param '-1', result: Num(neg=true, num=1, frac=), hash: 1931804178 (after 775 total execs)
    New path for param '-1-1', result: java.lang.NumberFormatException: Unknown char: -, hash: 1076200570 (after 1847 total execs)
    New path for param '77.', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: 1911280410 (after 2677 total execs)
    New path for param '-7?', result: java.lang.NumberFormatException: Unknown char: ?, hash: -1168567279 (after 2996 total execs)
    New path for param '-7.', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: 1076200583 (after 3143 total execs)
    New path for param '7777.77.', result: java.lang.NumberFormatException: Unknown char: ., hash: -1842655708 (after 4170 total execs)
    New path for param '-7......N', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: 1076200597 (after 4240 total execs)
    New path for param '-7.M', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: -1168573940 (after 5460 total execs)
    New path for param '7.>', result: java.lang.NumberFormatException: Decimal without trailing numbers(s), hash: 1075121433 (after 5822 total execs)
    New path for param '7.7?w77w00000000007', result: java.lang.NumberFormatException: Unknown char: ?, hash: 2062405589 (after 6697 total execs)
    New path for param '+3.3?', result: java.lang.NumberFormatException: Unknown char: ?, hash: 1121660674 (after 10603 total execs)
    New path for param '-733.3?', result: java.lang.NumberFormatException: Unknown char: ?, hash: 1944734728 (after 11765 total execs)
    New path for param '-733.3'', result: java.lang.NumberFormatException: Unknown char: ', hash: -1195485103 (after 11776 total execs)
    Fuzzer complete

This is every value that can cause a new set of branches in the parser function. The determination of path uniqueness
here is simply a hash of the set of hit branches. The number of times the branches are hit does not matter. In AFL, the
"hit counts" are grouped into buckets of 1, 2, 3, 4-7, 8-15, 16-31, 32-127, and 128+. Running the following will include
hit count buckets in the same way.

    path/to/gradle --no-daemon :examples:simple:run -Djwp.withHitCounts

Running that will result in a lot more values, because while some paths may be the same, the branches were invoked a
different number of bucketed times.