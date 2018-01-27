package jwp.fuzz;

import java.io.Closeable;
import java.util.Iterator;

public interface ParamProvider extends Closeable {
  Iterator<Object[]> iterator();

  default void onResult(ExecutionResult result) { }

  @Override
  default void close() { }
}
