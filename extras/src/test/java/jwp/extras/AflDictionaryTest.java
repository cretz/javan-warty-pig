package jwp.extras;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

public class AflDictionaryTest {

  @Test
  public void testSimpleDictionary() throws Exception {
    AflDictionary dict = AflDictionary.read(Files.readAllLines(
        Paths.get(getClass().getResource("gif.dict").toURI())));
    Assert.assertEquals(9, dict.entries.size());
    Assert.assertEquals(new AflDictionary.Entry("header_89a", 35, "89a".getBytes()), dict.entries.get(1));
    Assert.assertEquals(new AflDictionary.Entry("marker_3b", null, ";".getBytes()), dict.entries.get(4));
    Assert.assertEquals(new AflDictionary.Entry("section_21f9", null,
        new byte[] { 33, (byte) 0xF9, 0x04}), dict.entries.get(6));
  }
}
