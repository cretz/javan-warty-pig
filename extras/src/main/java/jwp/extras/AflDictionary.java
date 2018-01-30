package jwp.extras;

import java.nio.ByteBuffer;
import java.util.*;

/** Representation of a dictionary in <a href="http://lcamtuf.coredump.cx/afl/">AFL</a> format */
public class AflDictionary {

  /**
   * Read a dictionary from a set of lines in <a href="http://lcamtuf.coredump.cx/afl/">AFL</a> format. On error,
   * throws {@link InvalidDictionaryLine} with the line number.
   */
  public static AflDictionary read(Iterable<String> lines) {
    int index = 0;
    List<Entry> entries = new ArrayList<>();
    for (String line : lines) {
      int lineNum = ++index;
      try {
        Entry entry = Entry.fromLine(line);
        if (entry != null) entries.add(entry);
      } catch (Exception e) {
        throw new InvalidDictionaryLine(lineNum, e);
      }
    }
    return new AflDictionary(Collections.unmodifiableList(entries));
  }

  /** The set of entries read */
  public final List<Entry> entries;

  public AflDictionary(List<Entry> entries) {
    this.entries = entries;
  }

  /** An dictionary entry */
  public static class Entry {
    /** Read a single dictionary entry. Returns null if it's not an entry, throws on invalid format. */
    public static Entry fromLine(String line) {
      line = line.trim();
      // Skip empty lines and comments
      if (line.isEmpty() || line.charAt(0) == '#') return null;
      // Has to end with quote
      if (line.charAt(line.length() - 1) != '"') throw new IllegalArgumentException("Doesn't end with quote");
      // Label is all alphanumeric chars and underscore
      int currIndex = 0;
      StringBuilder label = new StringBuilder();
      while (currIndex < line.length()) {
        char chr = line.charAt(currIndex);
        if (!Character.isLetterOrDigit(chr) && chr != '_') break;
        currIndex++;
        label.append(chr);
      }
      // Number is all digits after at sign after that
      Integer level = null;
      if (label.length() > 0 && currIndex < line.length() && line.charAt(currIndex) == '@') {
        StringBuilder labelStr = new StringBuilder();
        currIndex++;
        while (currIndex < line.length()) {
          char chr = line.charAt(currIndex);
          if (!Character.isDigit(chr)) break;
          currIndex++;
          labelStr.append(chr);
        }
        level = Integer.parseInt(labelStr.toString());
      }
      // Skip whitespace and equal signs
      while (currIndex < line.length()) {
        char chr = line.charAt(currIndex);
        if (!Character.isWhitespace(chr) && chr != '=') break;
        currIndex++;
      }
      // Opening quote
      if (currIndex >= line.length() || line.charAt(currIndex) != '"')
        throw new IllegalArgumentException("No opening quote");
      currIndex++;
      // Get the stuff before last quote
      ByteBuffer value = ByteBuffer.allocate(line.length() - currIndex - 1);
      while (currIndex < line.length()) {
        char chr = line.charAt(currIndex);
        if (chr < 31 || chr > 127) throw new IllegalArgumentException("Value has invalid char");
        if (chr == '"') break;
        if (chr == '\\' && currIndex < line.length() - 1) {
          char escapedChr = line.charAt(++currIndex);
          if (escapedChr == '\\' || escapedChr == '"') value.put((byte) escapedChr);
          else if (escapedChr == 'x' && currIndex < line.length() - 2) {
            int chr1 = Character.digit(line.charAt(++currIndex), 16);
            int chr2 = Character.digit(line.charAt(++currIndex), 16);
            if (chr1 == -1 || chr2 == -1) throw new IllegalArgumentException("Invalid hex digit");
            currIndex++;
            value.put((byte) ((chr1 << 4) + chr2));
          } else throw new IllegalArgumentException("Unknown escaped char");
          continue;
        }
        currIndex++;
        value.put((byte) chr);
      }
      if (currIndex != line.length() - 1 || line.charAt(currIndex) != '"')
        throw new IllegalArgumentException("Unexpected extra chars at end");
      if (value.position() == 0) throw new IllegalArgumentException("Value is empty");
      return new Entry(label.toString(), level, Arrays.copyOf(value.array(), value.position()));
    }

    /** The label for the entry. Cannot be null, but can be empty. */
    public final String label;
    /** The level for the entry or null */
    public final Integer level;
     /** The byte value for the entry. Always non-null and non-empty. */
    public final byte[] value;

    public Entry(String label, Integer level, byte[] value) {
      this.label = label;
      this.level = level;
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Entry entry = (Entry) o;
      return Objects.equals(label, entry.label) &&
          Objects.equals(level, entry.level) &&
          Arrays.equals(value, entry.value);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(label, level);
      result = 31 * result + Arrays.hashCode(value);
      return result;
    }

    @Override
    public String toString() {
      return "Entry(label=" + label + ", level=" + level + ", value=" + Arrays.toString(value) + ")";
    }
  }

  /** Thrown on parse failure */
  public static class InvalidDictionaryLine extends RuntimeException {
    /** The 1-based line that failed */
    public final int lineNumber;

    public InvalidDictionaryLine(int lineNumber, Throwable cause) {
      super(cause);
      this.lineNumber = lineNumber;
    }

    @Override
    public String toString() { return super.toString() + " (line " + lineNumber + ")"; }
  }
}
