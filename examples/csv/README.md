## Example: CSV

This example shows how to generate test cases for fuzzed tests. It fuzzes [opencsv](http://opencsv.sourceforge.net/)'s
`CSVReader` and then builds `JUnit 4` tests that can then be executed. Each test represents a unique set of branches.
To create the tests, run the example from the repo root:

    path/to/gradle --no-daemon :examples:csv:run

This will fuzz the CSV reader for 30 seconds, outputting a test count every 500 test. Here is example output:

    Creating fuzzer
    Running fuzzer
    Added test number 500
    Added test number 1000
    Added test number 1500
    Added test number 2000
    Added test number 2500
    Added test number 3000
    Added test number 3500
    Added test number 4000
    Added test number 4500
    Added test number 5000
    Added test number 5500
    Added test number 6000
    Added test number 6500
    Added test number 7000
    Fuzzer complete
    Writing 7041 tests to src\test\java\jwp\examples\csv\MainTest.java

Now that it is written to a test file, we can run the tests:

    path/to/gradle --no-daemon :examples:csv:test

Which will show the number of executed tests like so:

    7041 of 7041 tests succeeded

To confirm we are hitting many branches, code coverage support has been added. The coverage can be seen by running the
tests and then the test report:

    path/to/gradle --no-daemon :examples:csv:test :examples:csv:jacocoTestReport

Then opening `examples/csv/build/jacocoHtml/index.html` in your browser will show the coverage. You can drill down into
the CSV reader and parser and see that we mostly hit all of the branches except for ones that are enabled by configs. To
hit more we could run the fuzzer longer and/or accept parameters for the other options.